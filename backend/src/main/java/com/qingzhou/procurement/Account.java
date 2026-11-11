package com.qingzhou.procurement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

public record Account(long id, String username, String name, String department, String role,boolean mustChangePassword) {
    public Account(long id,String username,String name,String department,String role){this(id,username,name,department,role,false);}
    public boolean is(String... roles) {
        for (String candidate : roles) if (role.equals(candidate)) return true;
        return false;
    }
    public void require(String... roles) {
        if (!is(roles)) throw new BusinessError(403, "当前账号没有执行此操作的权限");
    }
}

@Component
class Accounts {
    private final JdbcTemplate db;
    Accounts(JdbcTemplate db) { this.db = db; }
    Account current(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser"))
            throw new BusinessError(401, "请先登录");
        var rows=db.queryForList("SELECT * FROM app_user WHERE username=?",auth.getName());
        if(rows.isEmpty()) throw new BusinessError(401,"账号已失效，请重新登录");
        var row=rows.get(0);
        if(Boolean.TRUE.equals(row.get("deleted")))throw new BusinessError(401,"账号已删除，请联系管理员");
        if(!Boolean.TRUE.equals(row.get("enabled"))) throw new BusinessError(401,"账号已停用，请联系管理员");
        if(auth.getPrincipal() instanceof StoredUser user && (user.version!=((Number)row.get("auth_epoch")).intValue() || !user.passwordMarker.equals(StoredUser.marker(row.get("password_hash").toString()))))
            throw new BusinessError(401,"账号资料或权限已调整，请重新登录");
        return new Account(((Number)row.get("id")).longValue(),row.get("username").toString(),
            row.get("display_name").toString(),row.get("department").toString(),row.get("role").toString(),Boolean.TRUE.equals(row.get("must_change_password")));
    }
}
