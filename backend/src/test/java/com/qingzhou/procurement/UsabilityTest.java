package com.qingzhou.procurement;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class UsabilityTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;
 @Autowired ProcurementService procurement;@Autowired Notifications notifications;
 String key(){return UUID.randomUUID().toString();}
 long member(String name){db.update("INSERT INTO app_user(username,password_hash,display_name,department,role) VALUES (?,?,'测试成员','测试部门','EMPLOYEE')",name,passwords.encode("Original123!"));long id=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,name);db.update("INSERT INTO mutation_lock(actor_id) VALUES (?)",id);return id;}
 MockHttpSession login(String name,String password)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password",password)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 void reset(long id,String actor,String key,int code)throws Exception{mvc.perform(post("/api/users/"+id+"/reset-password").with(user(actor)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content("{\"password\":\"Temporary123!\",\"version\":0}")).andExpect(status().is(code));}
 @Test void resetRequiresAdministratorRevokesOldSessionsAndForcesChange()throws Exception{
  String name="reset_"+key().substring(0,10);long id=member(name);var old=login(name,"Original123!");
  reset(id,"employee",key(),403);reset(1,"admin",key(),409);String k=key();reset(id,"admin",k,200);reset(id,"admin",k,200);
  mvc.perform(get("/api/products").session(old)).andExpect(status().isUnauthorized());
  var temporary=login(name,"Temporary123!");
  mvc.perform(get("/api/auth/me").session(temporary)).andExpect(jsonPath("$.mustChangePassword").value(true));
  mvc.perform(get("/api/profile").session(temporary)).andExpect(status().isOk());
  mvc.perform(get("/api/products").session(temporary)).andExpect(status().isForbidden());
  mvc.perform(post("/api/profile/password").session(temporary).with(csrf()).contentType("application/json").content("{\"currentPassword\":\"Temporary123!\",\"newPassword\":\"MemberNew123!\"}")).andExpect(status().isOk());
  var fresh=login(name,"MemberNew123!");mvc.perform(get("/api/products").session(fresh)).andExpect(status().isOk());
  assertFalse(db.queryForObject("SELECT must_change_password FROM app_user WHERE id=?",Boolean.class,id));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='重置成员密码' AND target=?",Integer.class,name));
 }
 @Test void legacyServerPasswordResetAlsoRevokesExistingSessions()throws Exception{
  String name="legacy_"+key().substring(0,10);long id=member(name);var session=login(name,"Original123!");
  db.update("UPDATE app_user SET password_hash=?,version=version+1 WHERE id=?",passwords.encode("Changed123!"),id);
  mvc.perform(get("/api/products").session(session)).andExpect(status().isUnauthorized());
 }
 Map<String,Object> row(String sku,long supplier){return Map.of("sku",sku,"name","导入测试商品","category","办公用品","unit","件","price",10,"minStock",2,"supplierId",supplier);}
 @Test void previewReportsRowsWithoutWritingAndValidImportIsIdempotent()throws Exception{
  String a="IM"+key().substring(0,10),b="IM"+key().substring(0,10);
  String invalid=json.writeValueAsString(Map.of("rows",List.of(row(a,1),row(a,999999999))));
  mvc.perform(post("/api/products/import/preview").with(user("buyer")).with(csrf()).contentType("application/json").content(invalid)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.errors[0].row").value(3));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM product WHERE sku=?",Integer.class,a));
  String body=json.writeValueAsString(Map.of("rows",List.of(row(a,1),row(b,2))));
  mvc.perform(post("/api/products/import/preview").with(user("buyer")).with(csrf()).contentType("application/json").content(body)).andExpect(jsonPath("$.valid").value(true));
  mvc.perform(post("/api/products/import").with(user("employee")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  String k=key();for(int i=0;i<2;i++)mvc.perform(post("/api/products/import").with(user("buyer")).with(csrf()).header("Idempotency-Key",k).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM product WHERE sku IN (?,?)",Integer.class,a,b));
  assertEquals(0,db.queryForObject("SELECT SUM(stock) FROM product WHERE sku IN (?,?)",Integer.class,a,b));
 }
 @Test void importDoesNotOverwriteExistingSkuOrPartiallyInsert()throws Exception{
  String sku="IM"+key().substring(0,10),next="IM"+key().substring(0,10);
  db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id) VALUES (?,'原名称','办公','件',20,3,1)",sku);
  String body=json.writeValueAsString(Map.of("rows",List.of(row(next,1),row(sku,1))));
  mvc.perform(post("/api/products/import").with(user("admin")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM product WHERE sku=?",Integer.class,next));
  assertEquals("原名称",db.queryForObject("SELECT name FROM product WHERE sku=?",String.class,sku));
 }
 @Test void notificationsResolveNewAndOldTargetsWithoutBypassingDetailPermissions()throws Exception{
  var employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE");var r=procurement.createRequest(employee,new ApiController.RequestInput(1,1,"通知跳转测试"),key());
  String rid=r.get("id").toString(),number=r.get("number").toString();
  var rows=db.queryForList("SELECT target_id,target_type FROM app_notification WHERE target_id=? AND user_id=1",rid);
  assertFalse(rows.isEmpty());assertEquals(rid,rows.get(0).get("target_id"));assertEquals("requests",rows.get(0).get("target_type"));
  String nid=key();db.update("INSERT INTO app_notification(id,user_id,title,body,path) VALUES (?,1,'历史通知',?,'requests')",nid,number);
  var items=(List<?>)notifications.inbox(new Account(1,"admin","管理员","管理中心","ADMIN"),1,100,false).get("items");
  var old=(Map<?,?>)items.stream().filter(x->((Map<?,?>)x).get("id").equals(nid)).findFirst().orElseThrow();assertEquals(rid,old.get("target_id"));
  mvc.perform(get("/api/requests/"+rid).with(user("employee2"))).andExpect(status().isNotFound());
 }
}
