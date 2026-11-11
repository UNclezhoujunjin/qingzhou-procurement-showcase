package com.qingzhou.procurement;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FulfillmentService {
    private final Writes writes;
    private final LineItems lines;
    private final Notifications notifications;
    FulfillmentService(Writes writes,LineItems lines,Notifications notifications){this.writes=writes;this.lines=lines;this.notifications=notifications;}
    @Transactional public void receiveAll(Account a,String order) {
        fulfill(a,order,null,"receive-all-"+order,false);
    }
    @Transactional public Map<String,Object> receive(Account a,String order,WorkController.FulfillmentInput input,String key) {
        return fulfill(a,order,input,key,false);
    }
    @Transactional public Map<String,Object> purchaseReturn(Account a,String order,WorkController.FulfillmentInput input,String key) {
        return fulfill(a,order,input,key,true);
    }
    private Map<String,Object> fulfill(Account a,String id,WorkController.FulfillmentInput input,String key,boolean returning) {
        a.require("WAREHOUSE","ADMIN");
        String reason=input==null?"整单收货":Objects.toString(input.reason(),"").strip();
        if(returning&&reason.isBlank())throw new BusinessError(400,"请填写采购退货原因");
        var selected=new TreeMap<String,Integer>();
        if(input!=null)for(var item:input.lines())if(selected.put(item.orderLineId(),item.quantity())!=null)throw new BusinessError(400,"商品明细不能重复");
        String event=UUID.randomUUID().toString();
        String existing=writes.claim(a,returning?"PURCHASE_RETURN":"RECEIVE_ORDER",key,id+":"+selected+":"+reason,event);
        if(existing!=null)return writes.one("SELECT * FROM fulfillment_event WHERE id=?",existing);
        var parent=writes.one("SELECT request_id FROM purchase_order WHERE id=?",id);
        // All orders of a request share this lock, keeping its aggregate status correct under concurrency.
        var request=writes.one("SELECT * FROM purchase_request WHERE id=? FOR UPDATE",parent.get("request_id"));
        var order=writes.one("SELECT * FROM purchase_order WHERE id=? FOR UPDATE",id);
        if(input!=null&&input.version()!=((Number)order.get("version")).intValue())throw new BusinessError(409,"订单已更新，请刷新后核对收货或退货数量");
        if(!order.get("lifecycle_state").equals("ACTIVE")&&(!returning||!order.get("lifecycle_state").equals("TERMINATED")))
            throw new BusinessError(409,"订单已结束，不能继续收货");
        var items=lines.orders(id);
        if(input==null)for(var line:items){int remaining=((Number)line.get("remaining")).intValue();if(remaining>0)selected.put(line.get("id").toString(),remaining);}
        var known=new HashSet<String>();items.forEach(l->known.add(l.get("id").toString()));
        if(!known.containsAll(selected.keySet()))throw new BusinessError(400,"明细不属于当前订单");
        writes.db.update("INSERT INTO fulfillment_event(id,order_id,kind,reason,actor_id) VALUES (?,?,?,?,?)",event,id,returning?"PURCHASE_RETURN":"RECEIPT",reason,a.id());
        for(var line:items) {
            String lineId=line.get("id").toString();Integer quantity=selected.get(lineId);if(quantity==null)continue;
            int available=((Number)line.get(returning?"net_received":"remaining")).intValue();
            if(quantity<1||quantity>available)throw new BusinessError(409,returning?"退货数量超过该明细的净收货数量":"收货数量超过该明细的待收货数量");
            var p=writes.one("SELECT * FROM product WHERE id=? FOR UPDATE",line.get("product_id"));
            int before=((Number)p.get("stock")).intValue();
            int after=writes.stock(a,p,returning?-quantity:quantity,returning?"PURCHASE_RETURN":"PURCHASE_RECEIPT",order.get("number").toString(),null,lineId,event);
            notifications.lowStock(p,before,after);
            writes.db.update("UPDATE order_line SET "+(returning?"returned_quantity":"received_quantity")+"="+(returning?"returned_quantity":"received_quantity")+"+? WHERE id=?",quantity,lineId);
        }
        var updated=lines.orders(id);boolean full=updated.stream().allMatch(l->((Number)l.get("remaining")).intValue()==0);
        int net=updated.stream().mapToInt(l->((Number)l.get("net_received")).intValue()).sum();
        boolean anyReturn=updated.stream().anyMatch(l->((Number)l.get("returned_quantity")).intValue()>0);
        String state=full?"RECEIVED":net>0?"PARTIAL":anyReturn?"RETURNED":"OPEN";
        if(!selected.isEmpty()) {
            writes.db.update("UPDATE purchase_order SET status=?,fulfillment_status=?,version=version+1,received_at="+(full?"CURRENT_TIMESTAMP":"NULL")+" WHERE id=?",full?"RECEIVED":"OPEN",state,id);
            writes.reconcileRequest(request.get("id").toString());
            writes.audit(a,returning?"采购退货":"采购收货入库",order.get("number").toString(),"明细 "+selected.size()+" 项；"+reason);
            notifications.user(((Number)request.get("applicant_id")).longValue(),returning?"采购物资发生退货":full?"采购订单已收齐":"采购订单分批到货",order.get("number")+"；"+reason,"requests");
            notifications.user(((Number)order.get("buyer_id")).longValue(),returning?"采购订单需要跟进补货":"采购订单收货进展",order.get("number")+"；"+state,"orders");
        }
        return writes.one("SELECT * FROM fulfillment_event WHERE id=?",event);
    }
    List<Map<String,Object>> history(Account a,String id) {
        a.require("ADMIN","BUYER","WAREHOUSE");writes.one("SELECT id FROM purchase_order WHERE id=?",id);
        var events=writes.db.queryForList("SELECT e.*,u.display_name AS actor_name FROM fulfillment_event e JOIN app_user u ON u.id=e.actor_id WHERE e.order_id=? ORDER BY e.created_at DESC,e.id",id);
        for(var e:events)e.put("lines",writes.db.queryForList("SELECT m.quantity,m.balance,l.product_name,l.unit FROM stock_movement m JOIN order_line l ON l.id=m.order_line_id WHERE m.event_id=? ORDER BY l.product_id",e.get("id")));
        return events;
    }
}
