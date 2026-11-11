package com.qingzhou.procurement;
import java.util.*;
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
class ApprovalRoutingTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ApprovalRouting routing;@Autowired ProcurementService service;
 final Account employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE"),admin=new Account(1,"admin","管理员","管理中心","ADMIN"),manager=new Account(3,"manager","主管","产品研发部","MANAGER");
 String key(){return UUID.randomUUID().toString();}
 long member(String role,String department,boolean enabled){String name="route_"+key().substring(0,10);db.update("INSERT INTO app_user(username,password_hash,display_name,department,role,enabled) SELECT ?,password_hash,'审批测试人员',?,?,? FROM app_user WHERE id=1",name,department,role,enabled);long id=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,name);db.update("INSERT INTO mutation_lock(actor_id) VALUES (?)",id);return id;}
 ApiController.RequestInput input(Long approver){return new ApiController.RequestInput(1L,2,"指定审批人测试",null,approver);}
 @Test void candidatesExcludeSelfDisabledOtherDepartmentsAndForcedPasswordChanges()throws Exception{
  long own=member("MANAGER","路由部门",true),same=member("MANAGER","路由部门",true),other=member("MANAGER","其他部门",true),disabled=member("ADMIN","任意",false),forced=member("ADMIN","任意",true);
  db.update("UPDATE app_user SET must_change_password=TRUE WHERE id=?",forced);
  var a=new Account(own,"unused","提交人","路由部门","MANAGER");
  var ids=routing.candidates(a).stream().map(p->((Number)p.get("id")).longValue()).toList();
  assertTrue(ids.contains(same));assertTrue(ids.contains(1L));assertFalse(ids.contains(own));assertFalse(ids.contains(other));assertFalse(ids.contains(disabled));assertFalse(ids.contains(forced));
  mvc.perform(get("/api/requests/approvers").with(user("employee"))).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
 }
 @Test void missingAndIneligibleAssigneesAreRejected()throws Exception{
  mvc.perform(post("/api/requests").with(user("employee")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"productId\":1,\"quantity\":1,\"reason\":\"缺审批人\"}")).andExpect(status().isBadRequest());
  assertThrows(BusinessError.class,()->service.createRequest(employee,input(null),key()));
  assertThrows(BusinessError.class,()->service.createRequest(admin,input(1L),key()));
  assertThrows(BusinessError.class,()->service.createRequest(employee,input(2L),key()));
  long wrong=member("MANAGER","不相同的部门",true);
  assertThrows(BusinessError.class,()->service.createRequest(employee,input(wrong),key()));
  long disabled=member("ADMIN","任意",false);
  assertThrows(BusinessError.class,()->service.createRequest(employee,input(disabled),key()));
 }
 @Test void onlyAssignedPersonCanReviewAndNotificationContainsContext(){
  var r=service.createRequest(employee,input(3L),key());String id=r.get("id").toString();
  assertEquals(3L,((Number)r.get("approver_id")).longValue());assertNotNull(r.get("approver_name"));
  var notices=db.queryForList("SELECT user_id,body,target_id FROM app_notification WHERE target_id=? AND title='待您审批的采购申请'",id);
  assertEquals(1,notices.size());assertEquals(3L,((Number)notices.get(0).get("user_id")).longValue());
  String body=notices.get(0).get("body").toString();assertTrue(body.contains("申请人："));assertTrue(body.contains("金额：¥"));assertTrue(body.contains("待办："));
  assertEquals(403,assertThrows(BusinessError.class,()->service.review(admin,id,new ApiController.ReviewInput("APPROVED","",0))).status);
  service.review(manager,id,new ApiController.ReviewInput("APPROVED","核对通过",0));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM app_notification WHERE target_id=? AND title='待您审批的采购申请' AND read_at IS NULL",Integer.class,id));
 }
 @Test void reassignmentUpdatesDetailClosesOldTodoAndEnforcesNewOwner(){
  var r=service.createRequest(employee,input(3L),key());String id=r.get("id").toString();
  service.updateRequest(employee,id,new ApiController.RequestUpdateInput(input(1L),0));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM app_notification WHERE target_id=? AND user_id=3 AND read_at IS NULL",Integer.class,id));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM app_notification WHERE target_id=? AND user_id=1 AND read_at IS NULL",Integer.class,id));
  assertEquals(403,assertThrows(BusinessError.class,()->service.review(manager,id,new ApiController.ReviewInput("APPROVED","",1))).status);
  service.review(admin,id,new ApiController.ReviewInput("APPROVED","",1));
 }
 @Test void administratorCanSubmitToAnotherAdministratorAndNeverSelfApprove(){
  long reviewer=member("ADMIN","其他部门",true);
  var r=service.createRequest(admin,input(reviewer),key());String id=r.get("id").toString();
  assertThrows(BusinessError.class,()->service.review(admin,id,new ApiController.ReviewInput("APPROVED","",0)));
  service.review(new Account(reviewer,"unused","另一管理员","其他部门","ADMIN"),id,new ApiController.ReviewInput("APPROVED","",0));
 }
 @Test void noEligiblePeopleReturnsExplicitMessageWithoutChangingAccounts()throws Exception{
  var empty=org.mockito.Mockito.mock(ApprovalRouting.class);var account=org.mockito.Mockito.mock(Accounts.class);
  var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("admin","",List.of());
  org.mockito.Mockito.when(account.current(auth)).thenReturn(admin);
  org.mockito.Mockito.when(empty.candidates(admin)).thenReturn(List.of());
  var result=new ApprovalRoutingController(empty,account,org.mockito.Mockito.mock(Writes.class)).candidates(auth);
  assertEquals(false,result.get("available"));assertTrue(result.get("message").toString().contains("暂无可审批人员"));assertTrue(result.get("message").toString().contains("保存草稿"));
 }
 @Test void administratorCanReassignBeforeDisablingApprover()throws Exception{
  long approver=member("MANAGER",employee.department(),true);
  var request=service.createRequest(employee,input(approver),key());
  String id=request.get("id").toString(), number=request.get("number").toString();
  String change="{\"name\":\"测试成员\",\"department\":\"产品研发部\",\"role\":\"MANAGER\",\"enabled\":false,\"version\":0}";
  mvc.perform(put("/api/users/"+approver).with(user("admin")).with(csrf()).contentType("application/json").content(change))
      .andExpect(status().isConflict());
  mvc.perform(get("/api/users/"+approver+"/pending-approvals").with(user("admin")))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].number").value(number));
  String payload="{\"approverId\":1,\"version\":0,\"reason\":\"原负责人离岗\"}";
  mvc.perform(post("/api/requests/"+id+"/reassign").with(user("employee")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(payload))
      .andExpect(status().isForbidden());
  String token=key();
  for(int i=0;i<2;i++)mvc.perform(post("/api/requests/"+id+"/reassign").with(user("admin")).with(csrf()).header("Idempotency-Key",token).contentType("application/json").content(payload))
      .andExpect(status().isOk());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='改派采购审批' AND target=?",Integer.class,number));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM app_notification WHERE target_id=? AND user_id=? AND read_at IS NULL",Integer.class,id,approver));
  mvc.perform(put("/api/users/"+approver).with(user("admin")).with(csrf()).contentType("application/json").content(change))
      .andExpect(status().isOk());
  service.review(admin,id,new ApiController.ReviewInput("APPROVED","",1));
 }
}
