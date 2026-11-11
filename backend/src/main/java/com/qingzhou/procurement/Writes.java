package com.qingzhou.procurement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Writes {
    final JdbcTemplate db;
    Writes(JdbcTemplate db){this.db=db;}
    Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);if(rows.isEmpty())throw new BusinessError(404,"记录不存在");return rows.get(0);
    }
    String claim(Account a,String op,String key,String payload,String resource) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,80}"))throw new BusinessError(400,"请提供有效的 Idempotency-Key");
        one("SELECT actor_id FROM mutation_lock WHERE actor_id=? FOR UPDATE",a.id());
        String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
        var rows=db.queryForList("SELECT * FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=?",a.id(),op,key);
        if(!rows.isEmpty()) {
            if(!hash.equals(rows.get(0).get("fingerprint")))throw new BusinessError(409,"重复请求标识不能用于不同内容");
            return rows.get(0).get("resource_id").toString();
        }
        db.update("INSERT INTO idempotency_record(actor_id,operation,request_key,fingerprint,resource_id) VALUES (?,?,?,?,?)",a.id(),op,key,hash,resource);
        return null;
    }
    void reconcileRequest(String id) {
        long active=db.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_id=? AND lifecycle_state='ACTIVE' AND status<>'RECEIVED'",Long.class,id);
        long closed=db.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_id=? AND lifecycle_state<>'ACTIVE'",Long.class,id);
        db.update("UPDATE purchase_request SET status=?,lifecycle_state=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",active==0?"RECEIVED":"ORDERED",active==0&&closed>0?"CLOSED":"ACTIVE",id);
    }
    void audit(Account a,String action,String target,String detail) {
        db.update("INSERT INTO audit_log(actor_id,action,target,detail) VALUES (?,?,?,?)",a.id(),action,target,Notifications.clip(detail,500));
    }
    int stock(Account a,Map<String,Object> p,int delta,String business,String reference,String source,String line,String event) {
        long product=((Number)p.get("id")).longValue();
        int changed=db.update("UPDATE product SET stock=stock+?,stock_version=stock_version+1 WHERE id=? AND stock+?>=0",delta,product,delta);
        if(changed!=1)throw new BusinessError(409,"库存不足，无法完成本次操作");
        int balance=db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,product);
        db.update("INSERT INTO stock_movement(id,product_id,type,quantity,balance,reference,operator_id,business_type,source_movement_id,order_line_id,event_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),product,delta>0?"IN":"OUT",delta,balance,Notifications.clip(reference,100),a.id(),business,source,line,event);
        return balance;
    }
}
