package com.qingzhou.procurement;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Service
class ApprovalRouting {
 private final JdbcTemplate db;private final Notifications notifications;
 ApprovalRouting(JdbcTemplate db,Notifications notifications){this.db=db;this.notifications=notifications;}
 List<Map<String,Object>> candidates(Account a){a.require("EMPLOYEE","MANAGER","ADMIN");return db.queryForList("SELECT id,username,display_name,department,role FROM app_user WHERE enabled=TRUE AND deleted=FALSE AND must_change_password=FALSE AND id<>? AND (role='ADMIN' OR (role='MANAGER' AND department=?)) ORDER BY role,display_name,id",a.id(),a.department());}
 long validate(Account a,Long id){
  db.queryForObject("SELECT id FROM administration_lock WHERE id=1 FOR UPDATE",Integer.class);
  if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND enabled=TRUE AND deleted=FALSE",Long.class,a.id())==0)throw new BusinessError(409,"当前账号已停用或删除，不能提交申请");
  if(id==null)throw new BusinessError(400,"请选择审批负责人；若无人可选，请联系管理员配置同部门主管或其他管理员");
  var rows=db.queryForList("SELECT id,role,department,enabled,deleted,must_change_password FROM app_user WHERE id=?",id);
  if(rows.isEmpty())throw new BusinessError(409,"审批人不存在，请重新选择");var p=rows.get(0);
  if(Boolean.TRUE.equals(p.get("deleted"))||id==a.id()||!Boolean.TRUE.equals(p.get("enabled"))||Boolean.TRUE.equals(p.get("must_change_password"))||!(p.get("role").equals("ADMIN")||p.get("role").equals("MANAGER")&&a.department().equals(p.get("department"))))throw new BusinessError(409,"所选人员当前不可审批此申请，请刷新审批人员列表后重新选择");
  return id;
 }
 void notify(Account applicant,String id,String number,long approver){
  BigDecimal amount=db.queryForObject("SELECT COALESCE(SUM(quantity*unit_price),0) FROM request_line WHERE request_id=?",BigDecimal.class,id);
  notifications.closeApproval(id);
  notifications.user(approver,"待您审批的采购申请",number+"；申请人："+applicant.name()+"（"+applicant.department()+"）；金额：¥"+amount.setScale(2)+"；待办：请核对采购明细并通过或驳回。","requests");
 }
}

@RestController @RequestMapping("/api/requests")
class ApprovalRoutingController {
 private final ApprovalRouting routing;private final Accounts accounts;private final Writes writes;
 ApprovalRoutingController(ApprovalRouting routing,Accounts accounts,Writes writes){this.routing=routing;this.accounts=accounts;this.writes=writes;}
 @GetMapping("/approvers") Map<String,Object> candidates(Authentication auth){var people=routing.candidates(accounts.current(auth));return Map.of("items",people,"available",!people.isEmpty(),"message",people.isEmpty()?"暂无可审批人员，请先配置同部门主管或另一位管理员；可以先保存草稿。":"请选择一位审批负责人，提交后系统仅通知该负责人。");}

 record ReassignInput(@NotNull @Positive Long approverId,@NotNull @Min(0) Integer version,@NotBlank @Size(max=300) String reason){}

 private Account applicant(Map<String,Object> request){
  return new Account(((Number)request.get("applicant_id")).longValue(),"",
      request.get("applicant_name").toString(),request.get("department").toString(),"EMPLOYEE");
 }

 @GetMapping("/{id}/reassign-candidates")
 List<Map<String,Object>> reassignCandidates(Authentication auth,@PathVariable String id){
  accounts.current(auth).require("ADMIN");
  var request=writes.one(ProcurementService.REQUEST_SELECT+" WHERE r.id=?",id);
  return routing.candidates(applicant(request)).stream().filter(p->!Objects.equals(p.get("id"),request.get("approver_id"))).toList();
 }

 @PostMapping("/{id}/reassign") @Transactional
 Map<String,String> reassign(Authentication auth,@PathVariable String id,@Valid @RequestBody ReassignInput input,
     @RequestHeader("Idempotency-Key") String key){
  var actor=accounts.current(auth);actor.require("ADMIN");
  if(writes.claim(actor,"REASSIGN_APPROVAL",key,id+":"+input,id)!=null)return Map.of("id",id);
  writes.one("SELECT id FROM purchase_request WHERE id=? FOR UPDATE",id);
  writes.one("SELECT id FROM administration_lock WHERE id=1 FOR UPDATE");
  var request=writes.one(ProcurementService.REQUEST_SELECT+" WHERE r.id=?",id);
  if(!"ACTIVE".equals(request.get("lifecycle_state"))||!"PENDING".equals(request.get("status"))||
      ((Number)request.get("version")).intValue()!=input.version())throw new BusinessError(409,"申请状态已变化，请刷新后改派");
  var applicant=applicant(request);
  long replacement=routing.validate(applicant,input.approverId());
  if(Objects.equals(replacement,request.get("approver_id")))throw new BusinessError(409,"请选择另一位负责人");
  writes.db.update("UPDATE purchase_request SET approver_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",replacement,id);
  String number=request.get("number").toString();
  var next=writes.one("SELECT username FROM app_user WHERE id=?",replacement);
  writes.audit(actor,"改派采购审批",number,"原负责人："+Objects.toString(request.get("approver_username"),"未指定")+" → "+next.get("username")+"；原因："+input.reason().strip());
  routing.notify(applicant,id,number,replacement);
  return Map.of("id",id);
 }
}
