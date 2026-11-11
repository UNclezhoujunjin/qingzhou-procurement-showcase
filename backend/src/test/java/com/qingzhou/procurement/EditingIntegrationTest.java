package com.qingzhou.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
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

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class EditingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ProcurementService service;
    private final Account admin=new Account(1,"admin","陈予安","管理中心","ADMIN");
    private final Account employee=new Account(2,"employee","林小满","产品研发部","EMPLOYEE");

    @Test void catalogEditsPreservePriceSnapshotsAndStock() throws Exception {
        long id=newProduct();
        var request=service.createRequest(employee,new ApiController.RequestInput(id,2,"价格快照验证"),UUID.randomUUID().toString());
        var input=new ApiController.ProductUpdateInput(product("ED-"+id,"25.00","件"),0);
        mvc.perform(put("/api/products/"+id).with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/products/"+id).with(user("buyer")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isOk());
        assertEquals(0,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,id));
        assertEquals(0,new BigDecimal("10.00").compareTo(db.queryForObject("SELECT unit_price FROM purchase_request WHERE id=?",BigDecimal.class,request.get("id"))));
        assertEquals(409,assertThrows(BusinessError.class,()->service.updateProduct(admin,id,input)).status);
        assertEquals(409,assertThrows(BusinessError.class,()->service.updateProduct(admin,id,
            new ApiController.ProductUpdateInput(product("ED-"+id,"25.00","箱"),1))).status);
        assertEquals("件",db.queryForObject("SELECT unit FROM product WHERE id=?",String.class,id));
    }

    @Test void supplierEditingIsVersionedAndRestricted() throws Exception {
        String name="测试供应商-"+UUID.randomUUID();
        service.supplier(admin,new ApiController.SupplierInput(name,"原联系人","010-11112222"));
        long id=db.queryForObject("SELECT id FROM supplier WHERE name=?",Long.class,name);
        var input=new ApiController.SupplierUpdateInput(new ApiController.SupplierInput(name,"新联系人","010-33334444"),0);
        mvc.perform(put("/api/suppliers/"+id).with(user("buyer")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isOk());
        mvc.perform(put("/api/suppliers/"+id).with(user("buyer")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/suppliers/"+id).with(user("warehouse")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isForbidden());
        assertEquals("新联系人",db.queryForObject("SELECT contact FROM supplier WHERE id=?",String.class,id));
    }

    @Test void rejectedRequestsCanBeResubmittedOnlyByTheirOwner() throws Exception {
        var created=service.createRequest(employee,new ApiController.RequestInput(newProduct(),2,"原申请"),UUID.randomUUID().toString());
        String id=created.get("id").toString();
        long product=((Number)created.get("product_id")).longValue();
        service.review(admin,id,new ApiController.ReviewInput("REJECTED","数量需要调整",0));
        var input=new ApiController.RequestUpdateInput(new ApiController.RequestInput(product,1,"调整后重新提交"),1);
        mvc.perform(put("/api/requests/"+id).with(user("employee2")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/requests/"+id).with(user("employee")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.quantity").value(1)).andExpect(jsonPath("$.review_note").isEmpty())
            .andExpect(jsonPath("$.version").value(2));
        assertEquals(409,assertThrows(BusinessError.class,()->service.review(admin,id,new ApiController.ReviewInput("APPROVED","旧页面",0))).status);
        service.review(admin,id,new ApiController.ReviewInput("APPROVED","确认新需求",2));
        assertEquals(409,assertThrows(BusinessError.class,()->service.updateRequest(employee,id,
            new ApiController.RequestUpdateInput(input.data(),3))).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target=? AND action='修改并重新提交'",Integer.class,created.get("number")));
    }

    @Test void concurrentEditAndReviewCannotApproveStaleContent() throws Exception {
        var created=service.createRequest(employee,new ApiController.RequestInput(newProduct(),2,"审批冲突验证"),UUID.randomUUID().toString());
        String id=created.get("id").toString();
        long product=((Number)created.get("product_id")).longValue();
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var edit=executor.submit(()->attempt(start,()->service.updateRequest(employee,id,
                new ApiController.RequestUpdateInput(new ApiController.RequestInput(product,7,"修改的数量"),0))));
            var review=executor.submit(()->attempt(start,()->service.review(admin,id,new ApiController.ReviewInput("APPROVED","",0))));
            start.countDown();
            assertNotEquals(edit.get(10,TimeUnit.SECONDS),review.get(10,TimeUnit.SECONDS));
            var row=db.queryForMap("SELECT status,quantity,version FROM purchase_request WHERE id=?",id);
            assertEquals(1,((Number)row.get("version")).intValue());
            if(row.get("status").equals("APPROVED")) assertEquals(2,((Number)row.get("quantity")).intValue());
            else {assertEquals("PENDING",row.get("status"));assertEquals(7,((Number)row.get("quantity")).intValue());}
        } finally {executor.shutdownNow();}
    }

    @Test void disablingAndReenablingMembersRevokesBothActiveAndIdleSessions() throws Exception {
        String username="worker_"+UUID.randomUUID().toString().substring(0,8);
        long id=newUser(username);
        var active=login(username);var idle=login(username);
        updateUser(id,new ApiController.UserUpdateInput("测试成员","产品研发部","EMPLOYEE",false,0),200);
        mvc.perform(get("/api/products").session(active)).andExpect(status().isUnauthorized());
        assertTrue(active.isInvalid());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","TestAccount@2026"))
            .andExpect(status().isUnauthorized());
        updateUser(id,new ApiController.UserUpdateInput("测试成员","产品研发部","EMPLOYEE",true,1),200);
        mvc.perform(get("/api/products").session(idle)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(username))).andExpect(status().isOk());
        updateUser(id,new ApiController.UserUpdateInput("旧页面","错误部门","ADMIN",true,0),409);
        assertEquals("产品研发部",db.queryForObject("SELECT department FROM app_user WHERE id=?",String.class,id));
    }

    @Test void roleChangesRequireReloginAndAdministratorsCannotDisableThemselves() throws Exception {
        String username="buyer_"+UUID.randomUUID().toString().substring(0,8);
        long id=newUser(username);
        var oldSession=login(username);
        updateUser(id,new ApiController.UserUpdateInput("采购成员","采购部","BUYER",true,0),200);
        mvc.perform(get("/api/orders").session(oldSession)).andExpect(status().isUnauthorized());
        var newSession=login(username);
        mvc.perform(get("/api/orders").session(newSession)).andExpect(status().isOk());
        mvc.perform(post("/api/requests").session(newSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content("{\"approverId\":1,\"productId\":1,\"quantity\":1,\"reason\":\"不允许提交\"}"))
            .andExpect(status().isForbidden());
        int version=db.queryForObject("SELECT version FROM app_user WHERE id=1",Integer.class);
        updateUser(1,new ApiController.UserUpdateInput("管理员","管理中心","ADMIN",false,version),409);
        updateUser(1,new ApiController.UserUpdateInput("管理员","管理中心","EMPLOYEE",true,version),409);
        mvc.perform(get("/api/users").with(user("employee"))).andExpect(status().isForbidden());
        String response=mvc.perform(get("/api/users").with(user("admin"))).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("password_hash"));
    }

    private boolean attempt(CountDownLatch start,Runnable operation) throws Exception {
        start.await();try {operation.run();return true;}catch(BusinessError e){assertEquals(409,e.status);return false;}
    }
    private ApiController.ProductInput product(String sku,String price,String unit) {
        return new ApiController.ProductInput(sku,"编辑验证商品","办公设备",unit,new BigDecimal(price),5,1);
    }
    private long newProduct() {
        String sku="ED-"+UUID.randomUUID().toString().replace("-","");
        service.product(admin,product(sku,"10.00","件"));
        return db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);
    }
    private long newUser(String username) throws Exception {
        mvc.perform(post("/api/users").with(user("admin")).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",username,"name","测试成员","department","产品研发部","role","EMPLOYEE","password","TestAccount@2026"))))
            .andExpect(status().isOk());
        return db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
    }
    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","TestAccount@2026"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private void updateUser(long id,ApiController.UserUpdateInput input,int expected) throws Exception {
        mvc.perform(put("/api/users/"+id).with(user("admin")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().is(expected));
    }
}
