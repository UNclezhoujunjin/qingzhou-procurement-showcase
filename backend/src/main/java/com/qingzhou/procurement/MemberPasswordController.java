package com.qingzhou.procurement;
import java.util.*;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/users")
class MemberPasswordController {
 private final Writes writes;private final Accounts accounts;private final PasswordEncoder encoder;
 MemberPasswordController(Writes writes,Accounts accounts,PasswordEncoder encoder){this.writes=writes;this.accounts=accounts;this.encoder=encoder;}
 record ResetInput(@NotBlank @Size(min=8,max=64) String password,@NotNull @Min(0) Integer version){}
 @PostMapping("/{id}/reset-password") @Transactional
 Map<String,String> reset(Authentication auth,@PathVariable long id,@Valid @RequestBody ResetInput input,@RequestHeader("Idempotency-Key") String key){
  var a=accounts.current(auth);a.require("ADMIN");
  if(a.id()==id)throw new BusinessError(409,"请在个人中心修改自己的密码");
  if(input.password().getBytes(StandardCharsets.UTF_8).length>72)throw new BusinessError(400,"密码不能超过 72 个 UTF-8 字节");
  if(writes.claim(a,"RESET_MEMBER_PASSWORD",key,id+":"+input,String.valueOf(id))!=null)return Map.of("message","成员密码已重置");
  var member=writes.one("SELECT * FROM app_user WHERE id=? FOR UPDATE",id);
  if(Boolean.TRUE.equals(member.get("deleted")))throw new BusinessError(404,"成员已删除，不能重置密码");
  if(member.get("role").equals("ADMIN"))throw new BusinessError(409,"管理员密码请通过个人中心或服务器恢复");
  if(((Number)member.get("version")).intValue()!=input.version())throw new BusinessError(409,"成员资料已变化，请刷新后重试");
  writes.db.update("UPDATE app_user SET password_hash=?,version=version+1,auth_epoch=auth_epoch+1,must_change_password=TRUE WHERE id=?",encoder.encode(input.password()),id);
  writes.audit(a,"重置成员密码",member.get("username").toString(),"旧登录已失效；下次登录必须先修改密码");
  return Map.of("message","成员密码已重置，首次登录需修改密码");
 }
}
