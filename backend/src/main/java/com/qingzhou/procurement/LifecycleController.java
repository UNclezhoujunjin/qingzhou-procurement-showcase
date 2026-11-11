package com.qingzhou.procurement;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
class LifecycleController {
    private final Writes writes;private final Accounts accounts;private final ProcurementService service;
    private final Notifications notifications;private final ObjectMapper json;private final LineItems lines;
    LifecycleController(Writes writes,Accounts accounts,ProcurementService service,Notifications notifications,ObjectMapper json,LineItems lines){this.writes=writes;this.accounts=accounts;this.service=service;this.notifications=notifications;this.json=json;this.lines=lines;}
    record Transition(@NotBlank @Size(max=300) String reason,@NotNull @Min(0) Integer version){}
    record DraftInput(@NotBlank @Size(max=30000) String payload,@NotNull @Min(0) Integer version){}
    record DraftSubmitInput(@NotNull @Min(0) Integer version){}
    @GetMapping("/requests/{id}") Map<String,Object> request(Authentication auth,@PathVariable String id){
        var a=accounts.current(auth);var row=writes.one(ProcurementService.REQUEST_SELECT+" WHERE r.id=?",id);
        if(a.is("EMPLOYEE")&&((Number)row.get("applicant_id")).longValue()!=a.id()||a.is("MANAGER")&&!a.department().equals(row.get("department")))throw new BusinessError(404,"申请不存在");
        return lines.request(row);
    }
    @GetMapping("/orders/{id}") Map<String,Object> order(Authentication auth,@PathVariable String id){var a=accounts.current(auth);a.require("ADMIN","BUYER","WAREHOUSE");return lines.order(writes.one(ProcurementService.ORDER_SELECT+" WHERE o.id=?",id));}
    @GetMapping("/requests/{id}/orders") List<Map<String,Object>> relatedOrders(Authentication auth,@PathVariable String id){
        request(auth,id);
        var orders=writes.db.queryForList("""
            SELECT o.id,o.number,o.lifecycle_state,
              CASE WHEN o.lifecycle_state='ACTIVE' THEN o.fulfillment_status ELSE o.lifecycle_state END AS fulfillment_status,
              s.name AS supplier_name
            FROM purchase_order o JOIN supplier s ON s.id=o.supplier_id
            WHERE o.request_id=? ORDER BY o.created_at,o.id
            """,id);
        var items=writes.db.queryForList("""
            SELECT l.order_id,l.product_name,l.unit,l.quantity,
              l.received_quantity-l.returned_quantity AS net_received,
              l.quantity-l.received_quantity+l.returned_quantity AS remaining
            FROM order_line l JOIN purchase_order o ON o.id=l.order_id
            WHERE o.request_id=? ORDER BY l.product_id,l.id
            """,id);
        for(var order:orders)order.put("lines",items.stream().filter(l->l.get("order_id").equals(order.get("id"))).toList());
        return orders;
    }
    @GetMapping("/operations/{key}") Map<String,Object> operation(Authentication auth,@PathVariable String key){
        var a=accounts.current(auth);
        var rows=writes.db.queryForList("SELECT operation,resource_id FROM idempotency_record WHERE actor_id=? AND request_key=?",a.id(),key);
        return Map.of("committed",!rows.isEmpty(),"operations",rows);
    }
    @PostMapping("/requests/{id}/withdraw") @Transactional
    Map<String,Object> withdraw(Authentication auth,@PathVariable String id,@Valid @RequestBody Transition input,@RequestHeader("Idempotency-Key") String key){
        var a=accounts.current(auth);a.require("ADMIN","EMPLOYEE","MANAGER");
        if(writes.claim(a,"WITHDRAW_REQUEST",key,id+":"+input,id)!=null)return Map.of("id",id,"message","申请已撤回");
        var r=writes.one("SELECT * FROM purchase_request WHERE id=? FOR UPDATE",id);
        if(((Number)r.get("applicant_id")).longValue()!=a.id())throw new BusinessError(403,"只能撤回自己的申请");
        if(!r.get("status").equals("PENDING")||!r.get("lifecycle_state").equals("ACTIVE"))throw new BusinessError(409,"仅待审批申请可以撤回");
        version(r,input.version());
        writes.db.update("UPDATE purchase_request SET lifecycle_state='WITHDRAWN',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        notifications.closeApproval(id);
        writes.audit(a,"撤回采购申请",r.get("number").toString(),input.reason());
        return Map.of("id",id,"message","申请已撤回，可以修改后重新提交");
    }
    @PostMapping("/orders/{id}/close") @Transactional
    Map<String,Object> close(Authentication auth,@PathVariable String id,@Valid @RequestBody Transition input,@RequestHeader("Idempotency-Key") String key){
        var a=accounts.current(auth);a.require("ADMIN","BUYER");
        if(writes.claim(a,"CLOSE_ORDER",key,id+":"+input,id)!=null)return Map.of("id",id,"message","订单结束操作已完成");
        var parent=writes.one("SELECT request_id FROM purchase_order WHERE id=?",id);
        writes.one("SELECT id FROM purchase_request WHERE id=? FOR UPDATE",parent.get("request_id"));
        var o=writes.one("SELECT * FROM purchase_order WHERE id=? FOR UPDATE",id);version(o,input.version());
        if(!o.get("lifecycle_state").equals("ACTIVE"))throw new BusinessError(409,"订单已结束，请刷新查看");
        var items=lines.orders(id);
        if(items.stream().noneMatch(l->((Number)l.get("remaining")).intValue()>0))throw new BusinessError(409,"已全部交付的订单不能取消或终止");
        boolean received=items.stream().anyMatch(l->((Number)l.get("received_quantity")).intValue()>0);
        String state=received?"TERMINATED":"CANCELLED";
        writes.db.update("UPDATE purchase_order SET lifecycle_state=?,closure_reason=?,closed_at=CURRENT_TIMESTAMP,closed_by=?,version=version+1 WHERE id=?",state,input.reason().strip(),a.id(),id);
        writes.reconcileRequest(parent.get("request_id").toString());
        writes.audit(a,received?"终止订单剩余交付":"取消采购订单",o.get("number").toString(),input.reason()+"；已收退数量与库存保持不变");
        notifications.roles("采购订单已结束",o.get("number")+"；"+input.reason(),"orders","ADMIN","BUYER","WAREHOUSE");
        return Map.of("id",id,"state",state,"message",received?"剩余交付已终止，已收库存保留":"订单已取消");
    }
    @GetMapping("/drafts") List<Map<String,Object>> drafts(Authentication auth){var a=accounts.current(auth);a.require("ADMIN","EMPLOYEE","MANAGER");return writes.db.queryForList("SELECT id,payload,version,updated_at FROM request_draft WHERE actor_id=? AND submitted_request_id IS NULL AND discarded=FALSE ORDER BY updated_at DESC,id DESC LIMIT 100",a.id());}
    @PostMapping("/drafts/{id}/discard") @Transactional
    Map<String,String> discard(Authentication auth,@PathVariable String id,@Valid @RequestBody DraftSubmitInput input){
        var a=accounts.current(auth);
        a.require("ADMIN","EMPLOYEE","MANAGER");
        var draft=writes.one("SELECT * FROM request_draft WHERE id=? FOR UPDATE",id);
        owner(draft,a);
        if(draft.get("submitted_request_id")!=null)throw new BusinessError(409,"草稿已经提交，请查看采购申请");
        if(!Boolean.TRUE.equals(draft.get("discarded"))){
            version(draft,input.version());
            writes.db.update("UPDATE request_draft SET discarded=TRUE,payload='{}',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        }
        return Map.of("message","草稿已删除");
    }
    @GetMapping("/drafts/{id}") Map<String,Object> draft(Authentication auth,@PathVariable String id){var a=accounts.current(auth);var d=writes.one("SELECT * FROM request_draft WHERE id=?",id);owner(d,a);return d;}
    @PutMapping("/drafts/{id}") @Transactional
    Map<String,Object> save(Authentication auth,@PathVariable String id,@Valid @RequestBody DraftInput input){
        var a=accounts.current(auth);a.require("ADMIN","EMPLOYEE","MANAGER");validId(id);
        writes.one("SELECT actor_id FROM mutation_lock WHERE actor_id=? FOR UPDATE",a.id());
        parse(input.payload(),false);
        var existing=writes.db.queryForList("SELECT * FROM request_draft WHERE id=? FOR UPDATE",id);
        if(existing.isEmpty()){
            if(input.version()!=0)throw new BusinessError(409,"草稿不存在，请重新读取");
            writes.db.update("INSERT INTO request_draft(id,actor_id,payload) VALUES (?,?,?)",id,a.id(),input.payload());
        }else{
            var d=existing.get(0);owner(d,a);requireDraft(d);
            if(d.get("submitted_request_id")!=null)throw new BusinessError(409,"草稿已经提交，请查看采购申请");
            if(!d.get("payload").equals(input.payload())){version(d,input.version());writes.db.update("UPDATE request_draft SET payload=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",input.payload(),id);}
        }
        return writes.one("SELECT id,payload,version,submitted_request_id FROM request_draft WHERE id=?",id);
    }
    @PostMapping("/drafts/{id}/submit") @Transactional
    Map<String,Object> submit(Authentication auth,@PathVariable String id,@Valid @RequestBody DraftSubmitInput input,@RequestHeader("Idempotency-Key") String key){
        var a=accounts.current(auth);a.require("ADMIN","EMPLOYEE","MANAGER");
        writes.one("SELECT actor_id FROM mutation_lock WHERE actor_id=? FOR UPDATE",a.id());
        var d=writes.one("SELECT * FROM request_draft WHERE id=? FOR UPDATE",id);owner(d,a);requireDraft(d);
        if(d.get("submitted_request_id")!=null){writes.claim(a,"SUBMIT_DRAFT",key,id,d.get("submitted_request_id").toString());return Map.of("id",d.get("submitted_request_id"),"message","草稿已提交");}
        version(d,input.version());
        var result=service.createRequest(a,parse(d.get("payload").toString(),true),key);
        writes.claim(a,"SUBMIT_DRAFT",key,id,result.get("id").toString());
        writes.db.update("UPDATE request_draft SET submitted_request_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",result.get("id"),id);
        writes.audit(a,"提交采购草稿",id,"关联申请 "+result.get("number"));return result;
    }
    private ApiController.RequestInput parse(String value,boolean submitting){try{
        var input=json.readValue(value,ApiController.RequestInput.class);
        if(input==null)throw new IllegalArgumentException();
        if(submitting&&(input.reason()==null||input.reason().isBlank()||input.reason().length()>500))throw new BusinessError(400,"请填写 1～500 字的采购用途");
        return input;
    }catch(BusinessError e){throw e;}catch(Exception e){throw new BusinessError(400,"草稿格式不正确");}}
    private static void owner(Map<String,Object>d,Account a){if(((Number)d.get("actor_id")).longValue()!=a.id())throw new BusinessError(404,"草稿不存在");}
    private static void requireDraft(Map<String,Object> draft){
        if(Boolean.TRUE.equals(draft.get("discarded")))throw new BusinessError(409,"草稿已删除，请返回草稿列表");
    }
    private static void validId(String id){try{UUID.fromString(id);}catch(Exception e){throw new BusinessError(400,"草稿编号无效");}}
    private static void version(Map<String,Object> row,int expected){if(((Number)row.get("version")).intValue()!=expected)throw new BusinessError(409,"记录已更新，请重新读取并核对");}
}
