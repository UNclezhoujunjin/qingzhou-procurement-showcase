package com.qingzhou.procurement;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class LineItems {
    private final JdbcTemplate db;
    LineItems(JdbcTemplate db){this.db=db;}
    List<ApiController.RequestLineInput> normalize(ApiController.RequestInput input) {
        var lines=input.lines();
        if(lines==null||lines.isEmpty()) {
            if(input.productId()==null||input.productId()<1||input.quantity()==null||input.quantity()<1||input.quantity()>100000)
                throw new BusinessError(400,"请至少添加一项商品，数量为 1～100000");
            lines=List.of(new ApiController.RequestLineInput(input.productId(),input.quantity()));
        } else if(input.productId()!=null||input.quantity()!=null)throw new BusinessError(400,"不能同时提交新旧两种商品明细格式");
        if(lines.size()>100)throw new BusinessError(400,"一张申请最多 100 项商品");
        var seen=new HashSet<Long>();
        for(var line:lines) {
            if(line==null||line.productId()<1||line.quantity()<1||line.quantity()>100000)throw new BusinessError(400,"请检查商品和采购数量");
            if(!seen.add(line.productId()))throw new BusinessError(400,"同一商品请合并为一行");
        }
        return lines.stream().sorted(Comparator.comparingLong(ApiController.RequestLineInput::productId)).toList();
    }
    List<Map<String,Object>> snapshot(String request,List<ApiController.RequestLineInput> lines) {
        var result=new ArrayList<Map<String,Object>>();
        for(var line:lines) {
            var rows=db.queryForList("SELECT * FROM product WHERE id=? FOR UPDATE",line.productId());
            if(rows.isEmpty())throw new BusinessError(404,"采购商品不存在");
            var p=rows.get(0);if(Boolean.FALSE.equals(p.get("enabled")))throw new BusinessError(409,"商品已停用，请重新选择");String id=UUID.randomUUID().toString();
            db.update("INSERT INTO request_line(id,request_id,product_id,supplier_id,product_name,sku,unit,quantity,unit_price) VALUES (?,?,?,?,?,?,?,?,?)",
                id,request,line.productId(),p.get("supplier_id"),p.get("name"),p.get("sku"),p.get("unit"),line.quantity(),p.get("price"));
            result.add(p);
        }
        return result;
    }
    List<Map<String,Object>> requests(String id) {
        return db.queryForList("SELECT l.*,l.quantity*l.unit_price AS amount,s.name AS supplier_name FROM request_line l JOIN supplier s ON s.id=l.supplier_id WHERE l.request_id=? ORDER BY l.product_id",id);
    }
    List<Map<String,Object>> orders(String id) {
        return db.queryForList("SELECT l.*,l.quantity*l.unit_price AS amount,l.received_quantity-l.returned_quantity AS net_received,l.quantity-l.received_quantity+l.returned_quantity AS remaining FROM order_line l WHERE l.order_id=? ORDER BY l.product_id",id);
    }
    Map<String,Object> request(Map<String,Object> row) {return decorate(row,requests(row.get("id").toString()));}
    Map<String,Object> order(Map<String,Object> row) {return decorate(row,orders(row.get("id").toString()));}
    void batch(List<Map<String,Object>> rows,boolean orders) {
        if(rows.isEmpty())return;
        String marks=String.join(",",Collections.nCopies(rows.size(),"?"));
        String sql=orders
            ? "SELECT l.*,l.quantity*l.unit_price AS amount,l.received_quantity-l.returned_quantity AS net_received,l.quantity-l.received_quantity+l.returned_quantity AS remaining FROM order_line l WHERE l.order_id IN ("+marks+") ORDER BY l.product_id"
            : "SELECT l.*,l.quantity*l.unit_price AS amount,s.name AS supplier_name FROM request_line l JOIN supplier s ON s.id=l.supplier_id WHERE l.request_id IN ("+marks+") ORDER BY l.product_id";
        var grouped=new HashMap<String,List<Map<String,Object>>>();
        for(var line:db.queryForList(sql,rows.stream().map(r->r.get("id")).toArray()))
            grouped.computeIfAbsent(line.get(orders?"order_id":"request_id").toString(),k->new ArrayList<>()).add(line);
        for(var row:rows)decorate(row,grouped.getOrDefault(row.get("id").toString(),List.of()));
    }
    private Map<String,Object> decorate(Map<String,Object> row,List<Map<String,Object>> lines) {
        row.put("lines",lines);
        if(row.get("lifecycle_state")!=null&&!row.get("lifecycle_state").equals("ACTIVE")){
            if(row.containsKey("fulfillment_status"))row.put("fulfillment_status",row.get("lifecycle_state"));
            else row.put("status",row.get("lifecycle_state"));
        }
        if(!lines.isEmpty()) {
            row.put("amount",lines.stream().map(l->(BigDecimal)l.get("amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
            row.put("product_name",lines.get(0).get("product_name")+(lines.size()>1?" 等 "+lines.size()+" 项":""));
            row.put("quantity",lines.stream().mapToInt(l->((Number)l.get("quantity")).intValue()).sum());
        }
        return row;
    }
    void backfillLegacySeed() {
        db.update("INSERT INTO request_line(id,request_id,product_id,supplier_id,product_name,sku,unit,quantity,unit_price) SELECT r.id,r.id,r.product_id,p.supplier_id,p.name,p.sku,p.unit,r.quantity,r.unit_price FROM purchase_request r JOIN product p ON p.id=r.product_id WHERE NOT EXISTS (SELECT 1 FROM request_line l WHERE l.request_id=r.id)");
        db.update("INSERT INTO order_line(id,order_id,request_line_id,product_id,product_name,sku,unit,quantity,unit_price,received_quantity) SELECT o.id,o.id,l.id,l.product_id,l.product_name,l.sku,l.unit,l.quantity,l.unit_price,CASE WHEN o.status='RECEIVED' THEN l.quantity ELSE 0 END FROM purchase_order o JOIN request_line l ON l.request_id=o.request_id WHERE NOT EXISTS (SELECT 1 FROM order_line x WHERE x.order_id=o.id)");
    }
}
