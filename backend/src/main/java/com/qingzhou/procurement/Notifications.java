package com.qingzhou.procurement;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Notifications {
    private final JdbcTemplate db;
    Notifications(JdbcTemplate db){this.db=db;}
    void user(long id,String title,String body,String path) {
        var target=target(path,body);db.update("INSERT INTO app_notification(id,user_id,title,body,path,target_type,target_id) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,title,clip(body,500),path,target.get("target_type"),target.get("target_id"));
    }
    private Map<String,Object> target(String path,String body){
        if(!List.of("requests","orders").contains(path))return Map.of();
        var matcher=java.util.regex.Pattern.compile("(PR|PO)-[A-Z0-9]+(?:-[A-Z0-9]+)*").matcher(Objects.toString(body,""));
        if(!matcher.find())return Map.of();String number=matcher.group();
        String type=number.startsWith("PR-")?"requests":path.equals("requests")?"requests":"orders";
        String sql=number.startsWith("PR-")?"SELECT id FROM purchase_request WHERE number=?":type.equals("orders")?"SELECT id FROM purchase_order WHERE number=?":"SELECT request_id AS id FROM purchase_order WHERE number=?";
        var rows=db.queryForList(sql,number);return rows.isEmpty()?Map.of():Map.of("target_type",type,"target_id",rows.get(0).get("id"));
    }
    void roles(String title,String body,String path,String... roles) {
        for(var row:db.queryForList("SELECT id,role FROM app_user WHERE enabled=TRUE"))
            if(Arrays.asList(roles).contains(row.get("role")))user(((Number)row.get("id")).longValue(),title,body,path);
    }
    void closeApproval(String request){db.update("UPDATE app_notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE target_type='requests' AND target_id=? AND title='待您审批的采购申请'",request);}
    void approval(String department,long applicant,String number) {
        for(var row:db.queryForList("SELECT id,role,department FROM app_user WHERE enabled=TRUE")) {
            long id=((Number)row.get("id")).longValue();
            if(id!=applicant && (row.get("role").equals("ADMIN") || row.get("role").equals("MANAGER")&&row.get("department").equals(department)))
                user(id,"有新的采购申请待审批",number,"requests");
        }
    }
    void lowStock(Map<String,Object> product,int before,int after) {
        int min=((Number)product.get("min_stock")).intValue();
        if(before>=min&&after<min)roles("库存低于安全线",product.get("name")+"：剩余 "+after+" "+product.get("unit"),"inventory","ADMIN","BUYER");
    }
    Map<String,Object> inbox(Account a,int page,int size,boolean unread) {
        page=Math.max(1,page);size=Math.max(1,Math.min(100,size));
        String where=" WHERE user_id=?"+(unread?" AND read_at IS NULL":"");
        long total=db.queryForObject("SELECT COUNT(*) FROM app_notification"+where,Long.class,a.id());
        page=(int)Math.min(page,Math.max(1,(total+size-1)/size));
        var items=db.queryForList("SELECT * FROM app_notification"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",a.id(),size,(long)(page-1)*size);
        for(var row:items)if(row.get("target_id")==null)row.putAll(target(row.get("path").toString(),row.get("body").toString()));
        return Map.of("items",items,
            "total",total,
            "unread",db.queryForObject("SELECT COUNT(*) FROM app_notification WHERE user_id=? AND read_at IS NULL",Long.class,a.id()),"page",page,"size",size);
    }
    @Transactional public void read(Account a,String id) {
        int count=db.update("UPDATE app_notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=? AND user_id=?",id,a.id());
        if(count==0)throw new BusinessError(404,"通知不存在");
    }
    @Transactional public void readAll(Account a) {db.update("UPDATE app_notification SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND read_at IS NULL",a.id());}
    static String clip(String value,int max){return value.length()>max?value.substring(0,max-1)+"…":value;}
}
