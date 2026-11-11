package com.qingzhou.procurement;

import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class BrowseTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ProcurementService service;
 final Account admin=new Account(1,"admin","管理员","管理中心","ADMIN");
 long product(String sku){db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id) VALUES (?,?,'分页测试','件',10,5,1)",sku,sku);return db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);}
 @Test void pagingSearchAndWildcardEscaping() throws Exception {
  String prefix="PAGE"+UUID.randomUUID().toString().substring(0,12);
  for(int i=0;i<13;i++)product(prefix+i);
  mvc.perform(get("/api/browse/products").with(user("admin")).param("q",prefix).param("page","2").param("size","10"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(13)).andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.page").value(2));
  product(prefix+"%_literal");
  mvc.perform(get("/api/browse/products").with(user("admin")).param("q","%_literal"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
 }
 @Test void disableIsVersionedAuditedAndRejectsNewRequests() throws Exception {
  String sku="STATE"+UUID.randomUUID().toString().substring(0,12);long id=product(sku);
  mvc.perform(put("/api/browse/products/"+id+"/state").with(user("employee")).with(csrf()).contentType("application/json").content("{\"enabled\":false,\"version\":0}")).andExpect(status().isForbidden());
  mvc.perform(put("/api/browse/products/"+id+"/state").with(user("admin")).with(csrf()).contentType("application/json").content("{\"enabled\":false,\"version\":0}")).andExpect(status().isOk());
  mvc.perform(put("/api/browse/products/"+id+"/state").with(user("admin")).with(csrf()).contentType("application/json").content("{\"enabled\":true,\"version\":0}")).andExpect(status().isConflict());
  assertThrows(BusinessError.class,()->service.createRequest(new Account(2,"employee","员工","产品研发部","EMPLOYEE"),new ApiController.RequestInput(id,1,"停用测试"),UUID.randomUUID().toString()));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='停用商品' AND target=?",Integer.class,String.valueOf(id)));
  mvc.perform(get("/api/browse/products").with(user("admin")).param("q",sku).param("active","true")).andExpect(jsonPath("$.total").value(0));
  mvc.perform(put("/api/browse/products/"+id+"/state").with(user("admin")).with(csrf()).contentType("application/json").content("{\"enabled\":true,\"version\":1}")).andExpect(status().isOk());
  assertNotNull(service.createRequest(new Account(2,"employee","员工","产品研发部","EMPLOYEE"),new ApiController.RequestInput(id,1,"恢复测试"),UUID.randomUUID().toString()));
 }
 @Test void requestPagingRespectsScopeAndSearchesEveryLine() throws Exception {
  String sku="LINE"+UUID.randomUUID().toString().substring(0,12);long id=product(sku);
  var employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE");
  service.createRequest(employee,new ApiController.RequestInput(null,null,"分页明细",List.of(new ApiController.RequestLineInput(1,1),new ApiController.RequestLineInput(id,2))),UUID.randomUUID().toString());
  mvc.perform(get("/api/browse/requests").with(user("employee")).param("q",sku)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].lines.length()").value(2)).andExpect(jsonPath("$.items[0].amount").isNumber());
  mvc.perform(get("/api/browse/requests").with(user("employee2")).param("q",sku)).andExpect(jsonPath("$.total").value(0));
  mvc.perform(get("/api/browse/orders").with(user("employee"))).andExpect(status().isForbidden());
 }
 @Test void disabledProductStillAllowsHistoricalOrderReceipt() throws Exception {
  long id=product("HISTORY"+UUID.randomUUID().toString().substring(0,12));
  var e=new Account(2,"employee","员工","产品研发部","EMPLOYEE");
  var r=service.createRequest(e,new ApiController.RequestInput(id,3,"历史履约"),UUID.randomUUID().toString());
  service.review(admin,r.get("id").toString(),new ApiController.ReviewInput("APPROVED","",0));
  var order=service.order(admin,r.get("id").toString());
  mvc.perform(put("/api/browse/products/"+id+"/state").with(user("admin")).with(csrf()).contentType("application/json").content("{\"enabled\":false,\"version\":0}")).andExpect(status().isOk());
  service.receive(admin,order.get("id").toString());
  assertEquals(3,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,id));
  assertFalse(db.queryForObject("SELECT enabled FROM product WHERE id=?",Boolean.class,id));
 }
 @Test void orderPagesIncludeSupplierSplitLinesAndSummary() throws Exception {
  long id=product("ORDER"+UUID.randomUUID().toString().substring(0,12));var e=new Account(2,"employee","员工","产品研发部","EMPLOYEE");
  var r=service.createRequest(e,new ApiController.RequestInput(id,3,"分页订单"),UUID.randomUUID().toString());
  service.review(admin,r.get("id").toString(),new ApiController.ReviewInput("APPROVED","",0));
  var order=service.order(admin,r.get("id").toString());
  mvc.perform(get("/api/browse/orders").with(user("admin")).param("q",order.get("number").toString())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].lines[0].quantity").value(3));
  assertNotNull(service.overview(admin).get("categories"));
 }
}
