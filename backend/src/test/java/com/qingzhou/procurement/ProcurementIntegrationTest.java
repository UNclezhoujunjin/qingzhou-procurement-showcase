package com.qingzhou.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}", "app.demo=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProcurementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ProcurementService service;
    private final Account admin=new Account(1,"admin","陈予安","管理中心","ADMIN");
    private final Account warehouse=new Account(5,"warehouse","沈禾","仓储部","WAREHOUSE");

    @Test void anonymousAndCsrfAreEnforced() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/requests").with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test void realPasswordLoginCreatesSessionAndLogoutInvalidatesIt() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","admin").param("password","wrong-password"))
            .andExpect(status().isUnauthorized());
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username","admin").param("password","Qingzhou@2026"))
            .andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
    }

    @Test void employeesAndManagersHaveDataScopes() throws Exception {
        JsonNode employee=json.readTree(mvc.perform(get("/api/requests").with(user("employee"))).andReturn().getResponse().getContentAsString());
        assertTrue(employee.size()>0);
        employee.forEach(r->assertEquals(2,r.get("applicant_id").asInt()));
        JsonNode manager=json.readTree(mvc.perform(get("/api/requests").with(user("manager"))).andReturn().getResponse().getContentAsString());
        manager.forEach(r->assertEquals("产品研发部",r.get("department").asText()));
        mvc.perform(post("/api/requests/demo-request-3/review").with(user("manager")).with(csrf())
            .contentType("application/json").content("{\"decision\":\"APPROVED\",\"note\":\"\",\"version\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/issue").with(user("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content("{\"approverId\":1,\"productId\":1,\"quantity\":1,\"reason\":\"越权领用\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void completeFlowAndRepeatedReceiptKeepStockConsistent() throws Exception {
        String key=UUID.randomUUID().toString();
        String body="{\"approverId\":3,\"productId\":1,\"quantity\":3,\"reason\":\"集成测试工位采购\"}";
        var created=mvc.perform(post("/api/requests").with(user("employee")).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        String requestId=json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/requests").with(user("employee")).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(body)).andExpect(jsonPath("$.id").value(requestId));
        mvc.perform(post("/api/requests/"+requestId+"/review").with(user("employee")).with(csrf())
            .contentType("application/json").content("{\"decision\":\"APPROVED\",\"note\":\"\",\"version\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/requests/"+requestId+"/review").with(user("manager")).with(csrf())
            .contentType("application/json").content("{\"decision\":\"APPROVED\",\"note\":\"预算确认\",\"version\":0}"))
            .andExpect(status().isOk());
        var ordered=mvc.perform(post("/api/requests/"+requestId+"/order").with(user("buyer")).with(csrf()))
            .andExpect(status().isOk()).andReturn();
        String orderId=json.readTree(ordered.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/requests/"+requestId+"/order").with(user("buyer")).with(csrf()))
            .andExpect(jsonPath("$.id").value(orderId));
        int before=db.queryForObject("SELECT stock FROM product WHERE id=1",Integer.class);
        for(int i=0;i<2;i++) mvc.perform(post("/api/orders/"+orderId+"/receive").with(user("warehouse")).with(csrf())).andExpect(status().isOk());
        assertEquals(before+3,db.queryForObject("SELECT stock FROM product WHERE id=1",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE reference=(SELECT number FROM purchase_order WHERE id=?)",Integer.class,orderId));
        assertEquals("RECEIVED",db.queryForObject("SELECT status FROM purchase_request WHERE id=?",String.class,requestId));
    }

    @Test void selfApprovalAndIllegalTransitionsAreRejected() {
        String reviewer="reviewer_"+UUID.randomUUID().toString().substring(0,8);
        db.update("INSERT INTO app_user(username,password_hash,display_name,department,role) SELECT ?,password_hash,'审批管理员','管理中心','ADMIN' FROM app_user WHERE id=1",reviewer);
        long reviewerId=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,reviewer);
        var created=service.createRequest(admin,new ApiController.RequestInput(1L,1,"管理员申请",null,reviewerId),UUID.randomUUID().toString());
        var error=assertThrows(BusinessError.class,()->service.review(admin,created.get("id").toString(),new ApiController.ReviewInput("APPROVED","",0)));
        assertEquals(403,error.status);
        var transition=assertThrows(BusinessError.class,()->service.order(admin,created.get("id").toString()));
        assertEquals(409,transition.status);
    }

    @Test void issueIdempotencyAndInsufficientStockRollback() {
        long product=newProduct(10);
        String key=UUID.randomUUID().toString();
        var input=new ApiController.IssueInput(product,4,"测试人员 / 工位领用");
        var first=service.issue(warehouse,input,key);
        var repeat=service.issue(warehouse,input,key);
        assertEquals(first.get("id"),repeat.get("id"));
        assertEquals(6,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,product));
        assertThrows(BusinessError.class,()->service.issue(warehouse,new ApiController.IssueInput(product,5,"不同内容"),key));
        String failedKey=UUID.randomUUID().toString();
        assertThrows(BusinessError.class,()->service.issue(warehouse,new ApiController.IssueInput(product,7,"库存不足"),failedKey));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE request_key=?",Integer.class,failedKey));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE product_id=?",Integer.class,product));
    }

    @Test void concurrentIssuesCannotOverdrawStock() throws Exception {
        long product=newProduct(10);
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            Future<Boolean> a=executor.submit(()->attemptIssue(admin,product,start));
            Future<Boolean> b=executor.submit(()->attemptIssue(warehouse,product,start));
            start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(3,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,product));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE product_id=?",Integer.class,product));
        } finally {executor.shutdownNow();}
    }

    @Test void invalidInputCannotCreateNegativeQuantity() throws Exception {
        mvc.perform(post("/api/requests").with(user("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content("{\"approverId\":1,\"productId\":1,\"quantity\":-2,\"reason\":\"无效输入\"}"))
            .andExpect(status().isBadRequest());
    }

    private boolean attemptIssue(Account a,long product,CountDownLatch start) throws Exception {
        start.await();
        try {service.issue(a,new ApiController.IssueInput(product,7,"并发测试"),UUID.randomUUID().toString());return true;}
        catch(BusinessError e) {assertEquals(409,e.status);return false;}
    }
    private long newProduct(int stock) {
        String sku="TEST-"+UUID.randomUUID().toString().replace("-","");
        service.product(admin,new ApiController.ProductInput(sku,"测试物资","测试分类","件",new BigDecimal("10.00"),1,1));
        long id=db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);
        db.update("UPDATE product SET stock=? WHERE id=?",stock,id);
        return id;
    }
}
