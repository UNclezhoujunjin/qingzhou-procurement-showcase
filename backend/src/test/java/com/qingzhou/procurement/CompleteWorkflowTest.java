package com.qingzhou.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
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
class CompleteWorkflowTest {
    @Autowired JdbcTemplate db;@Autowired ProcurementService procurement;@Autowired FulfillmentService fulfillment;
    @Autowired InventoryOperations inventory;@Autowired Notifications notifications;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    final Account admin=new Account(1,"admin","管理员","管理中心","ADMIN"),employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE"),warehouse=new Account(5,"warehouse","仓管","仓储部","WAREHOUSE");

    @Test void multipleLinesSplitBySupplierAndKeepSnapshots() {
        long a=product(1,0),b=product(2,0),c=product(1,0);
        var request=procurement.createRequest(employee,new ApiController.RequestInput(null,null,"多商品采购",List.of(new ApiController.RequestLineInput(a,2),new ApiController.RequestLineInput(b,3),new ApiController.RequestLineInput(c,1))),key());
        assertEquals(3,((List<?>)request.get("lines")).size());assertEquals(0,new BigDecimal("60").compareTo((BigDecimal)request.get("amount")));
        String id=request.get("id").toString();procurement.review(admin,id,new ApiController.ReviewInput("APPROVED","",0));
        var result=procurement.order(admin,id);var orders=(List<?>)result.get("orders");assertEquals(2,orders.size());
        assertEquals(2,((List<?>)procurement.order(admin,id).get("orders")).size());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM order_line WHERE order_id IN (SELECT id FROM purchase_order WHERE request_id=?)",Integer.class,id));
        assertEquals(2,db.queryForObject("SELECT COUNT(DISTINCT supplier_id) FROM purchase_order WHERE request_id=?",Integer.class,id));
        assertThrows(BusinessError.class,()->procurement.createRequest(employee,new ApiController.RequestInput(null,null,"重复商品",List.of(new ApiController.RequestLineInput(a,1),new ApiController.RequestLineInput(a,2))),key()));
        assertEquals(409,assertThrows(BusinessError.class,()->procurement.updateProduct(admin,b,new ApiController.ProductUpdateInput(new ApiController.ProductInput("CHANGED-"+b,"单位验证","办公设备","箱",BigDecimal.TEN,3,2),0))).status);
    }

    @Test void partialReceiptReturnReplacementAndIdempotencyKeepQuantitiesBalanced() {
        var order=order(product(1,0),10);String id=order.get("id").toString(),line=line(id);long product=((Number)firstLine(id).get("product_id")).longValue();
        var first=input(id,line,6,"首批到货");String requestKey=key();
        fulfillment.receive(warehouse,id,first,requestKey);fulfillment.receive(warehouse,id,first,requestKey);
        assertEquals(6,stock(product));assertEquals("PARTIAL",state(id));
        assertEquals(409,assertThrows(BusinessError.class,()->fulfillment.receive(warehouse,id,input(id,line,5,"超收"),key())).status);
        fulfillment.purchaseReturn(warehouse,id,input(id,line,2,"两件损坏，退回补货"),key());assertEquals(4,stock(product));
        assertEquals(6,((Number)firstLine(id).get("remaining")).intValue());
        assertEquals(409,assertThrows(BusinessError.class,()->fulfillment.purchaseReturn(warehouse,id,input(id,line,5,"超退"),key())).status);
        procurement.issue(warehouse,new ApiController.IssueInput(product,3,"先领用三件"),key());
        assertEquals(409,assertThrows(BusinessError.class,()->fulfillment.purchaseReturn(warehouse,id,input(id,line,2,"实物库存不足"),key())).status);
        assertEquals(1,stock(product));assertEquals(2,((Number)firstLine(id).get("returned_quantity")).intValue());
        fulfillment.receive(warehouse,id,input(id,line,6,"补齐到货"),key());
        assertEquals(7,stock(product));assertEquals("RECEIVED",state(id));
        assertEquals("RECEIVED",db.queryForObject("SELECT status FROM purchase_request WHERE id=?",String.class,order.get("request_id")));
        assertEquals(3,fulfillment.history(admin,id).size());
    }

    @Test void concurrentReceiptsCannotDuplicateSameDisplayedQuantity() throws Exception {
        var order=order(product(1,0),10);String id=order.get("id").toString(),line=line(id);long product=((Number)firstLine(id).get("product_id")).longValue();
        var input=input(id,line,6,"");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->attempt(start,()->fulfillment.receive(admin,id,input,key())));
            var b=pool.submit(()->attempt(start,()->fulfillment.receive(warehouse,id,input,key())));
            start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(6,stock(product));
        }finally{pool.shutdownNow();}
    }

    @Test void separateSupplierReceiptsConvergeToCompletedRequest() throws Exception {
        long p=product(1,0),q=product(2,0);
        var request=procurement.createRequest(employee,new ApiController.RequestInput(null,null,"同时收货",List.of(new ApiController.RequestLineInput(p,2),new ApiController.RequestLineInput(q,3))),key());
        String id=request.get("id").toString();procurement.review(admin,id,new ApiController.ReviewInput("APPROVED","",0));procurement.order(admin,id);
        var orders=db.queryForList("SELECT id FROM purchase_order WHERE request_id=? ORDER BY supplier_id",id);
        var pool=Executors.newFixedThreadPool(2);try {
            var a=pool.submit(()->fulfillment.receiveAll(admin,orders.get(0).get("id").toString()));
            var b=pool.submit(()->fulfillment.receiveAll(warehouse,orders.get(1).get("id").toString()));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals("RECEIVED",db.queryForObject("SELECT status FROM purchase_request WHERE id=?",String.class,id));
        }finally{pool.shutdownNow();}
    }

    @Test void countRejectsStaleStockAndRecordsBothVarianceAndNoVariance() {
        long p=product(1,8);procurement.issue(warehouse,new ApiController.IssueInput(p,2,"盘点期间领用"),key());
        assertEquals(409,assertThrows(BusinessError.class,()->inventory.count(warehouse,new WorkController.CountInput(List.of(new WorkController.CountLineInput(p,7,0)),"旧库存盘点"),key())).status);
        assertEquals(6,stock(p));
        var input=new WorkController.CountInput(List.of(new WorkController.CountLineInput(p,4,stockVersion(p))),"实物复核少两件");String key=key();
        var count=inventory.count(warehouse,input,key);assertEquals(count.get("id"),inventory.count(warehouse,input,key).get("id"));assertEquals(4,stock(p));
        assertEquals(-2,db.queryForObject("SELECT difference FROM stock_count_line WHERE count_id=?",Integer.class,count.get("id")));
        inventory.count(warehouse,new WorkController.CountInput(List.of(new WorkController.CountLineInput(p,4,stockVersion(p))),"数量一致复核"),key());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE product_id=? AND business_type='COUNT_ADJUSTMENT'",Integer.class,p));
        long empty=product(1,0);inventory.count(warehouse,new WorkController.CountInput(List.of(new WorkController.CountLineInput(empty,0,0)),"零库存盘点"),key());
        assertEquals(409,assertThrows(BusinessError.class,()->procurement.updateProduct(admin,empty,new ApiController.ProductUpdateInput(new ApiController.ProductInput("COUNT-"+empty,"盘点后单位锁定","办公设备","箱",BigDecimal.TEN,3,1),0))).status);
    }

    @Test void issueReturnsAreLinkedBoundedAndIdempotent() {
        long p=product(1,10);var issue=procurement.issue(warehouse,new ApiController.IssueInput(p,4,"领用归还测试"),key());String id=issue.get("id").toString();
        String key=key();var input=new WorkController.ReturnInput(3,"归还三件");
        inventory.returnIssue(warehouse,id,input,key);inventory.returnIssue(warehouse,id,input,key);assertEquals(9,stock(p));
        assertEquals(409,assertThrows(BusinessError.class,()->inventory.returnIssue(warehouse,id,new WorkController.ReturnInput(2,"超额归还"),key())).status);
        inventory.returnIssue(warehouse,id,new WorkController.ReturnInput(1,"全部归还"),key());assertEquals(10,stock(p));
        assertTrue(inventory.issues(warehouse).stream().noneMatch(i->i.get("id").equals(id)));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE source_movement_id=?",Integer.class,id));
    }

    @Test void warehouseEndpointsAndBackupFilesArePermissionProtected() throws Exception {
        mvc.perform(get("/api/inventory/issues").with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/counts").with(user("buyer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/backups").with(user("warehouse"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/counts").with(user("warehouse")).with(csrf()).contentType("application/json").content("{\"lines\":[{\"approverId\":1,\"productId\":1,\"countedQuantity\":-1,\"stockVersion\":0}],\"reason\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void notificationsArePersonalAndReadActionsAreScoped() throws Exception {
        var request=procurement.createRequest(employee,new ApiController.RequestInput(product(1,0),1,"通知测试"),key());
        String notice=db.queryForObject("SELECT id FROM app_notification WHERE user_id=1 AND target_id=?",String.class,request.get("id"));
        mvc.perform(post("/api/notifications/"+notice+"/read").with(user("employee")).with(csrf())).andExpect(status().isNotFound());
        assertNull(db.queryForObject("SELECT read_at FROM app_notification WHERE id=?",java.sql.Timestamp.class,notice));
        mvc.perform(post("/api/notifications/"+notice+"/read").with(user("admin")).with(csrf())).andExpect(status().isOk());
        assertNotNull(db.queryForObject("SELECT read_at FROM app_notification WHERE id=?",java.sql.Timestamp.class,notice));
        notifications.user(employee.id(),"个人通知","测试","requests");notifications.readAll(employee);
        assertEquals(0,((Number)notifications.inbox(employee,1,20,false).get("unread")).intValue());
        for(var row:(List<?>)notifications.inbox(employee,1,100,false).get("items"))assertEquals(2L,((Number)((Map<?,?>)row).get("user_id")).longValue());
    }

    @Test void profileUpdatesOnlyPersonalFieldsAndKeepsExistingSessions() throws Exception {
        String username=newUser();var session=login(username,"TestAccount@2026");var idle=login(username,"TestAccount@2026");
        mvc.perform(put("/api/profile").session(session).with(csrf()).contentType("application/json")
            .content("{\"name\":\"新名字\",\"email\":\"test@example.com\",\"phone\":\"123456\",\"version\":0,\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk());assertFalse(session.isInvalid());
        mvc.perform(get("/api/profile").session(idle)).andExpect(status().isOk());
        mvc.perform(get("/api/profile").session(login(username,"TestAccount@2026"))).andExpect(jsonPath("$.role").value("EMPLOYEE")).andExpect(jsonPath("$.display_name").value("新名字"));
    }

    @Test void passwordChangeRequiresOldPasswordAndRevokesAllOldLogins() throws Exception {
        String username=newUser();var session=login(username,"TestAccount@2026");var idle=login(username,"TestAccount@2026");
        mvc.perform(post("/api/profile/password").session(session).with(csrf()).contentType("application/json")
            .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewPass!\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/profile/password").session(session).with(csrf()).contentType("application/json")
            .content("{\"currentPassword\":\"TestAccount@2026\",\"newPassword\":\"NewPass!\"}")).andExpect(status().isOk());
        assertTrue(session.isInvalid());mvc.perform(get("/api/products").session(idle)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","TestAccount@2026")).andExpect(status().isUnauthorized());
        assertNotNull(login(username,"NewPass!"));
        assertFalse(db.queryForObject("SELECT password_hash FROM app_user WHERE username=?",String.class,username).contains("NewPassword"));
    }

    private long product(int supplier,int stock){String sku="WF-"+key().replace("-","");procurement.product(admin,new ApiController.ProductInput(sku,"业务验证物资","办公设备","件",BigDecimal.TEN,3,supplier));long id=db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);db.update("UPDATE product SET stock=? WHERE id=?",stock,id);return id;}
    private Map<String,Object> order(long p,int quantity){var r=procurement.createRequest(employee,new ApiController.RequestInput(p,quantity,"收货测试"),key());procurement.review(admin,r.get("id").toString(),new ApiController.ReviewInput("APPROVED","",0));return procurement.order(admin,r.get("id").toString());}
    private Map<String,Object> firstLine(String id){return db.queryForMap("SELECT *,quantity-received_quantity+returned_quantity AS remaining FROM order_line WHERE order_id=?",id);}
    private String line(String id){return firstLine(id).get("id").toString();}
    private WorkController.FulfillmentInput input(String id,String line,int quantity,String reason){return new WorkController.FulfillmentInput(List.of(new WorkController.LineQuantity(line,quantity)),reason,db.queryForObject("SELECT version FROM purchase_order WHERE id=?",Integer.class,id));}
    private int stock(long p){return db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,p);}
    private String state(String id){return db.queryForObject("SELECT fulfillment_status FROM purchase_order WHERE id=?",String.class,id);}
    private int stockVersion(long p){return db.queryForObject("SELECT stock_version FROM product WHERE id=?",Integer.class,p);}
    private String key(){return UUID.randomUUID().toString();}
    private boolean attempt(CountDownLatch start,Runnable run)throws Exception{start.await();try{run.run();return true;}catch(BusinessError e){assertEquals(409,e.status);return false;}}
    private String newUser()throws Exception{String username="self_"+key().substring(0,8);mvc.perform(post("/api/users").with(user("admin")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"name","测试成员","department","产品研发部","role","EMPLOYEE","password","TestAccount@2026")))).andExpect(status().isOk());return username;}
    private MockHttpSession login(String username,String password)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
}
