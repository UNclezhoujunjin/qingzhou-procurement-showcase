package com.qingzhou.procurement;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/users")
class MemberRemovalController {
    private final Writes writes; private final Accounts accounts;
    MemberRemovalController(Writes writes,Accounts accounts){this.writes=writes;this.accounts=accounts;}
    record RemoveInput(@NotNull @Min(0) Integer version,@NotBlank @Size(max=64) String username,@NotBlank @Size(max=300) String reason){}

    @PostMapping("/{id}/remove") @Transactional
    Map<String,String> remove(Authentication auth,@PathVariable long id,@Valid @RequestBody RemoveInput input,@RequestHeader("Idempotency-Key") String key){
        var a=accounts.current(auth);a.require("ADMIN");
        if(a.id()==id)throw new BusinessError(409,"不能删除当前登录的管理员账号");
        if(writes.claim(a,"REMOVE_MEMBER",key,id+":"+input,String.valueOf(id))!=null)return Map.of("message","成员已删除，历史业务记录已保留");
        // Routing validation and member changes use the same lock so a concurrent
        // submission cannot assign a new pending approval after this check.
        writes.one("SELECT id FROM administration_lock WHERE id=1 FOR UPDATE");
        if(writes.db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND role='ADMIN' AND enabled=TRUE AND deleted=FALSE",Long.class,a.id())==0)throw new BusinessError(401,"管理员账号状态已变化，请重新登录");
        var member=writes.one("SELECT * FROM app_user WHERE id=? FOR UPDATE",id);
        if(Boolean.TRUE.equals(member.get("deleted")))throw new BusinessError(404,"成员已删除，请刷新成员列表");
        if(((Number)member.get("version")).intValue()!=input.version())throw new BusinessError(409,"成员资料已变化，请读取最新版本后重新确认");
        if(!member.get("username").equals(input.username().strip()))throw new BusinessError(400,"请输入该成员的登录账号以确认删除");
        long pending=writes.db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE status='PENDING' AND lifecycle_state='ACTIVE' AND (approver_id=? OR applicant_id=?)",Long.class,id,id);
        if(pending>0)throw new BusinessError(409,"该成员还有 "+pending+" 笔待审批申请（本人提交或负责审批），请先审批、撤回或更换负责人后再删除");
        if(member.get("role").equals("ADMIN")&&writes.db.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deleted=FALSE AND id<>?",Long.class,id)==0)
            throw new BusinessError(409,"至少需要保留一位启用的管理员");
        writes.db.update("UPDATE app_user SET deleted=TRUE,enabled=FALSE,version=version+1,auth_epoch=auth_epoch+1 WHERE id=?",id);
        writes.db.update("UPDATE app_notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE user_id=?",id);
        writes.audit(a,"删除团队成员",member.get("username").toString(),"保留历史业务记录；原因："+input.reason().strip());
        return Map.of("message","成员已删除，历史业务记录已保留");
    }
}
