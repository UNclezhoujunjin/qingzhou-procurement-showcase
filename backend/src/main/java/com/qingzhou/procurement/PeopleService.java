package com.qingzhou.procurement;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PeopleService {
    private final Writes writes;private final PasswordEncoder passwords;
    PeopleService(Writes writes,PasswordEncoder passwords){this.writes=writes;this.passwords=passwords;}
    Map<String,Object> profile(Account a) {
        return writes.one("SELECT id,username,display_name,department,role,email,phone,version,must_change_password FROM app_user WHERE id=?",a.id());
    }
    @Transactional public void profile(Account a,WorkController.ProfileInput input) {
        int changed=writes.db.update("UPDATE app_user SET display_name=?,email=?,phone=?,version=version+1 WHERE id=? AND version=?",input.name().strip(),input.email().strip(),input.phone().strip(),a.id(),input.version());
        if(changed!=1)throw new BusinessError(409,"个人资料已更新，请刷新后重试");
        writes.audit(a,"修改个人资料",a.username(),"更新姓名与联系方式");
    }
    @Transactional public void password(Account a,WorkController.PasswordInput input) {
        var user=writes.one("SELECT * FROM app_user WHERE id=? FOR UPDATE",a.id());
        if(!passwords.matches(input.currentPassword(),user.get("password_hash").toString()))throw new BusinessError(400,"当前密码不正确");
        if(input.newPassword().getBytes(StandardCharsets.UTF_8).length>72)throw new BusinessError(400,"新密码的 UTF-8 长度不能超过 72 字节");
        if(passwords.matches(input.newPassword(),user.get("password_hash").toString()))throw new BusinessError(400,"新密码不能与当前密码相同");
        writes.db.update("UPDATE app_user SET password_hash=?,version=version+1,auth_epoch=auth_epoch+1,must_change_password=FALSE WHERE id=?",passwords.encode(input.newPassword()),a.id());
        writes.audit(a,"修改登录密码",a.username(),"密码已更新，旧登录已失效");
    }
}
