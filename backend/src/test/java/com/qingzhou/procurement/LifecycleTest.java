package com.qingzhou.procurement;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class LifecycleTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ProcurementService service;@Autowired FulfillmentService fulfillment;
 @Autowired LifecycleController lifecycle;@Autowired ObjectMapper json;
 final Account admin=new Account(1,"admin","管理员","管理中心","ADMIN"),employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE"),warehouse=new Account(5,"warehouse","仓管","仓储部","WAREHOUSE");
 String key(){return UUID.randomUUID().toString();}
 long product(int supplier){String sku="LC"+key().substring(0,12);db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id) VALUES (?,?,'业务测试','件',10,5,?)",sku,sku,supplier);return db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);}
 Map<String,Object> request(long p){return service.createRequest(employee,new ApiController.RequestInput(p,4,"生命周期测试"),key());}
 Map<String,Object> order(long p){var r=request(p);service.review(admin,r.get("id").toString(),new ApiController.ReviewInput("APPROVED","",0));return service.order(admin,r.get("id").toString());}
 int stock(long p){return db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,p);}
 JsonNode save(String id,String actor,String payload,int version,int status) throws Exception{return json.readTree(mvc.perform(put("/api/drafts/"+id).with(user(actor)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("payload",payload,"version",version)))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());}
 void transition(String path,String actor,int version,String key,int status) throws Exception {mvc.perform(post(path).with(user(actor)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(Map.of("version",version,"reason","项目调整")))).andExpect(status().is(status));}
 @Test void draftsArePrivateVersionedAndSubmitExactlyOnce() throws Exception{
  long p=product(1);String id=key();String payload=json.writeValueAsString(new ApiController.RequestInput(p,4,"草稿提交"));
  save(id,"employee",payload,0,200);save(id,"employee2",payload,0,404);
  mvc.perform(get("/api/drafts").with(user("employee2"))).andExpect(jsonPath("$[?(@.id=='"+id+"')]").isEmpty());
  String changed=json.writeValueAsString(new ApiController.RequestInput(p,5,"修改草稿"));save(id,"employee",changed,0,200);save(id,"employee",payload,0,409);
  String k=key();transition("/api/drafts/"+id+"/submit","employee",1,k,200);transition("/api/drafts/"+id+"/submit","employee",1,k,200);
  transition("/api/drafts/"+id+"/submit","employee",1,key(),200);
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE product_id=?",Integer.class,p));
  mvc.perform(get("/api/operations/"+k).with(user("employee"))).andExpect(jsonPath("$.committed").value(true));
  mvc.perform(get("/api/operations/"+k).with(user("employee2"))).andExpect(jsonPath("$.committed").value(false));
 }
 @Test void incompleteDraftDoesNotCreateRequestOrAllowInvalidSubmission() throws Exception{
  String id=key();save(id,"employee","{\"lines\":[],\"reason\":\"\"}",0,200);
  transition("/api/drafts/"+id+"/submit","employee",0,key(),400);
  assertNull(db.queryForObject("SELECT submitted_request_id FROM request_draft WHERE id=?",String.class,id));
 }
 @Test void withdrawalStopsApprovalAndCanBeEditedAndResubmitted() throws Exception{
  long p=product(1);var r=request(p);String id=r.get("id").toString(),k=key();
  transition("/api/requests/"+id+"/withdraw","employee2",0,key(),403);
  transition("/api/requests/"+id+"/withdraw","employee",0,k,200);transition("/api/requests/"+id+"/withdraw","employee",0,k,200);
  assertThrows(BusinessError.class,()->service.review(admin,id,new ApiController.ReviewInput("APPROVED","",1)));
  assertEquals("WITHDRAWN",service.requests(employee).stream().filter(x->x.get("id").equals(id)).findFirst().orElseThrow().get("status"));
  mvc.perform(get("/api/browse/requests").with(user("employee")).param("q",r.get("number").toString()).param("status","WITHDRAWN")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
  service.updateRequest(employee,id,new ApiController.RequestUpdateInput(new ApiController.RequestInput(p,2,"重新提交"),1));
  service.review(admin,id,new ApiController.ReviewInput("APPROVED","",2));
  assertEquals("ACTIVE",db.queryForObject("SELECT lifecycle_state FROM purchase_request WHERE id=?",String.class,id));
 }
 @Test void cancelIsAuditedIdempotentAndCannotReceive() throws Exception{
  long p=product(1);var o=order(p);String id=o.get("id").toString(),k=key();
  transition("/api/orders/"+id+"/close","warehouse",0,key(),403);
  transition("/api/orders/"+id+"/close","buyer",9,key(),409);
  transition("/api/orders/"+id+"/close","buyer",0,k,200);transition("/api/orders/"+id+"/close","buyer",0,k,200);
  assertEquals(0,stock(p));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='取消采购订单' AND target=?",Integer.class,o.get("number")));
  mvc.perform(get("/api/browse/orders").with(user("buyer")).param("q",o.get("number").toString()).param("status","CANCELLED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].fulfillment_status").value("CANCELLED"));
  assertThrows(BusinessError.class,()->fulfillment.receiveAll(warehouse,id));
  assertEquals("CLOSED",db.queryForObject("SELECT lifecycle_state FROM purchase_request WHERE id=?",String.class,o.get("request_id")));
 }
 @Test void terminatePreservesStockAndAllowsReturnWithoutReopening() throws Exception{
  long p=product(1);var o=order(p);String id=o.get("id").toString();
  String line=db.queryForObject("SELECT id FROM order_line WHERE order_id=?",String.class,id);
  fulfillment.receive(warehouse,id,new WorkController.FulfillmentInput(List.of(new WorkController.LineQuantity(line,2)),"首批",0),key());
  transition("/api/orders/"+id+"/close","buyer",1,key(),200);assertEquals(2,stock(p));
  assertThrows(BusinessError.class,()->fulfillment.receive(warehouse,id,new WorkController.FulfillmentInput(List.of(new WorkController.LineQuantity(line,1)),"迟到",2),key()));
  fulfillment.purchaseReturn(warehouse,id,new WorkController.FulfillmentInput(List.of(new WorkController.LineQuantity(line,1)),"退回",2),key());
  assertEquals(1,stock(p));assertEquals("TERMINATED",db.queryForObject("SELECT lifecycle_state FROM purchase_order WHERE id=?",String.class,id));
  assertEquals("CLOSED",db.queryForObject("SELECT lifecycle_state FROM purchase_request WHERE id=?",String.class,o.get("request_id")));
 }
 @Test void closingOneSplitOrderDoesNotCloseOtherOrders() throws Exception{
  long p=product(1),q=product(2);
  var r=service.createRequest(employee,new ApiController.RequestInput(null,null,"拆单取消",List.of(new ApiController.RequestLineInput(p,2),new ApiController.RequestLineInput(q,2))),key());
  String rid=r.get("id").toString();service.review(admin,rid,new ApiController.ReviewInput("APPROVED","",0));service.order(admin,rid);
  var ids=db.queryForList("SELECT id FROM purchase_order WHERE request_id=? ORDER BY supplier_id",String.class,rid);
  transition("/api/orders/"+ids.get(0)+"/close","buyer",0,key(),200);
  assertEquals("ORDERED",db.queryForObject("SELECT status FROM purchase_request WHERE id=?",String.class,rid));
  fulfillment.receiveAll(warehouse,ids.get(1));
  assertEquals("CLOSED",db.queryForObject("SELECT lifecycle_state FROM purchase_request WHERE id=?",String.class,rid));
 }
 @Test void concurrentCloseAndReceiptCannotBothApplyStaleVersion() throws Exception{
  long p=product(1);var o=order(p);String id=o.get("id").toString();String line=db.queryForObject("SELECT id FROM order_line WHERE order_id=?",String.class,id);
  var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   var close=pool.submit(()->{start.await();try{lifecycle.close(UsernamePasswordAuthenticationToken.authenticated("buyer","",List.of()),id,new LifecycleController.Transition("关闭",0),key());return true;}catch(BusinessError e){assertEquals(409,e.status);return false;}});
   var receive=pool.submit(()->{start.await();try{fulfillment.receive(warehouse,id,new WorkController.FulfillmentInput(List.of(new WorkController.LineQuantity(line,4)),"收货",0),key());return true;}catch(BusinessError e){assertEquals(409,e.status);return false;}});
   start.countDown();assertNotEquals(close.get(10,TimeUnit.SECONDS),receive.get(10,TimeUnit.SECONDS));assertTrue(stock(p)==0||stock(p)==4);
  }finally{pool.shutdownNow();}
 }
 @Test void fullyReceivedOrderCannotBeCancelled() throws Exception{
  long p=product(1);var o=order(p);String id=o.get("id").toString();fulfillment.receiveAll(warehouse,id);
  transition("/api/orders/"+id+"/close","buyer",1,key(),409);assertEquals(4,stock(p));
 }
 @Test void productCreateResponseLossCanBeRetriedWithSameKey() throws Exception{
  String sku="RETRY"+key().substring(0,12),k=key();
  String body=json.writeValueAsString(new ApiController.ProductInput(sku,"重试商品","办公","件",java.math.BigDecimal.TEN,1,1));
  for(int i=0;i<2;i++)mvc.perform(post("/api/products").with(user("admin")).with(csrf()).header("Idempotency-Key",k).contentType("application/json").content(body)).andExpect(status().isOk());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product WHERE sku=?",Integer.class,sku));
  mvc.perform(post("/api/products").with(user("admin")).with(csrf()).header("Idempotency-Key",k).contentType("application/json").content(body.replace("重试商品","其他内容"))).andExpect(status().isConflict());
  mvc.perform(get("/api/operations/"+k)).andExpect(status().isUnauthorized());
 }
}
