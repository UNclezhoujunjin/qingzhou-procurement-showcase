package com.qingzhou.procurement;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryOperations {
    private final Writes writes;private final Notifications notifications;
    InventoryOperations(Writes writes,Notifications notifications){this.writes=writes;this.notifications=notifications;}
    List<Map<String,Object>> issues(Account a) {
        a.require("ADMIN","WAREHOUSE");
        return writes.db.queryForList("SELECT m.*,p.name AS product_name,p.sku,p.unit,u.display_name AS operator_name,-m.quantity-m.returned_quantity AS remaining FROM stock_movement m JOIN product p ON p.id=m.product_id JOIN app_user u ON u.id=m.operator_id WHERE m.business_type='ISSUE' AND -m.quantity>m.returned_quantity ORDER BY m.created_at DESC LIMIT 500");
    }
    @Transactional public Map<String,Object> returnIssue(Account a,String id,WorkController.ReturnInput input,String key) {
        a.require("ADMIN","WAREHOUSE");String result=UUID.randomUUID().toString();
        String existing=writes.claim(a,"ISSUE_RETURN",key,id+":"+input,result);
        if(existing!=null)return writes.one("SELECT * FROM stock_movement WHERE id=?",existing);
        var issue=writes.one("SELECT * FROM stock_movement WHERE id=? FOR UPDATE",id);
        if(!issue.get("business_type").equals("ISSUE"))throw new BusinessError(400,"只能归还领用出库的物资");
        int remaining=-((Number)issue.get("quantity")).intValue()-((Number)issue.get("returned_quantity")).intValue();
        if(input.quantity()>remaining)throw new BusinessError(409,"归还数量超过该笔领用的未归还数量");
        var p=writes.one("SELECT * FROM product WHERE id=? FOR UPDATE",issue.get("product_id"));
        writes.db.update("UPDATE product SET stock=stock+?,stock_version=stock_version+1 WHERE id=?",input.quantity(),p.get("id"));
        int balance=((Number)p.get("stock")).intValue()+input.quantity();
        writes.db.update("UPDATE stock_movement SET returned_quantity=returned_quantity+? WHERE id=?",input.quantity(),id);
        writes.db.update("INSERT INTO stock_movement(id,product_id,type,quantity,balance,reference,operator_id,business_type,source_movement_id) VALUES (?,?,'IN',?,?,?,?,'ISSUE_RETURN',?)",
            result,p.get("id"),input.quantity(),balance,input.reason().strip(),a.id(),id);
        writes.audit(a,"领用物资归还",id,"归还 "+input.quantity()+"；"+input.reason());
        return writes.one("SELECT * FROM stock_movement WHERE id=?",result);
    }
    @Transactional public Map<String,Object> count(Account a,WorkController.CountInput input,String key) {
        a.require("ADMIN","WAREHOUSE");String id=UUID.randomUUID().toString();
        var sorted=input.lines().stream().sorted(Comparator.comparingLong(WorkController.CountLineInput::productId)).toList();
        if(sorted.stream().map(WorkController.CountLineInput::productId).distinct().count()!=sorted.size())throw new BusinessError(400,"盘点商品不能重复");
        String existing=writes.claim(a,"STOCK_COUNT",key,sorted+":"+input.reason(),id);
        if(existing!=null)return writes.one("SELECT * FROM stock_count WHERE id=?",existing);
        String number="SC-"+id.replace("-","").toUpperCase(Locale.ROOT);
        writes.db.update("INSERT INTO stock_count(id,number,reason,actor_id) VALUES (?,?,?,?)",id,number,input.reason().strip(),a.id());
        for(var line:sorted) {
            var p=writes.one("SELECT * FROM product WHERE id=? FOR UPDATE",line.productId());
            if(((Number)p.get("stock_version")).intValue()!=line.stockVersion())throw new BusinessError(409,"盘点期间库存已变化，请刷新后重新核对实物数量");
            int before=((Number)p.get("stock")).intValue(),delta=line.countedQuantity()-before;
            writes.db.update("INSERT INTO stock_count_line(id,count_id,product_id,expected_quantity,counted_quantity,difference) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,line.productId(),before,line.countedQuantity(),delta);
            if(delta!=0) {
                writes.stock(a,p,delta,"COUNT_ADJUSTMENT",number,null,null,null);
                notifications.lowStock(p,before,line.countedQuantity());
            }
        }
        writes.audit(a,"库存盘点",number,"盘点 "+sorted.size()+" 项；"+input.reason());
        return writes.one("SELECT * FROM stock_count WHERE id=?",id);
    }
    List<Map<String,Object>> counts(Account a) {
        a.require("ADMIN","WAREHOUSE");
        var rows=writes.db.queryForList("SELECT c.*,u.display_name AS actor_name FROM stock_count c JOIN app_user u ON u.id=c.actor_id ORDER BY c.created_at DESC LIMIT 100");
        for(var c:rows)c.put("lines",writes.db.queryForList("SELECT l.*,p.name AS product_name,p.unit FROM stock_count_line l JOIN product p ON p.id=l.product_id WHERE l.count_id=? ORDER BY p.id",c.get("id")));
        return rows;
    }
}
