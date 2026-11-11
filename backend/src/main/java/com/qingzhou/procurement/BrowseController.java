package com.qingzhou.procurement;

import java.util.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController @RequestMapping("/api/browse")
class BrowseController {
    private final JdbcTemplate db; private final Accounts accounts; private final LineItems lines;
    BrowseController(JdbcTemplate db,Accounts accounts,LineItems lines){this.db=db;this.accounts=accounts;this.lines=lines;}
    record StateInput(@NotNull Boolean enabled,@NotNull @Min(0) Integer version){}
    @PutMapping("/products/{id}/state") @Transactional
    Map<String,String> state(Authentication auth,@PathVariable long id,@Valid @RequestBody StateInput input){
        var a=accounts.current(auth);a.require("ADMIN","BUYER");
        int n=db.update("UPDATE product SET enabled=?,version=version+1 WHERE id=? AND version=?",input.enabled(),id,input.version());
        if(n!=1)throw new BusinessError(409,"商品已变化，请刷新后重试");
        db.update("INSERT INTO audit_log(actor_id,action,target,detail) VALUES (?,?,?,?)",a.id(),input.enabled()?"恢复商品":"停用商品",String.valueOf(id),"仅影响新采购选择，历史单据和库存保留");
        return Map.of("message",input.enabled()?"商品已恢复":"商品已停用");
    }
    @GetMapping("/{kind}")
    Map<String,Object> browse(Authentication auth,@PathVariable String kind,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String status,
        @RequestParam(defaultValue="ALL") String category,@RequestParam(defaultValue="ALL") String active,
        @RequestParam(defaultValue="false") boolean lowStock,
        @RequestParam(defaultValue="ALL") String requestType,
        @RequestParam(required=false) LocalDate fromDate,@RequestParam(required=false) LocalDate toDate,
        @RequestParam(defaultValue="") String operator){
        var a=accounts.current(auth);page=Math.max(1,page);size=Math.max(1,Math.min(100,size));
        if(kind.equals("movements")||kind.equals("audit"))return history(a,kind,page,size,q,fromDate,toDate,operator);
        String select,from,scope=" WHERE 1=1",filter="";var base=new ArrayList<Object>();var params=new ArrayList<Object>();
        if(kind.equals("products")){
            select="SELECT p.*,s.name AS supplier_name";from=" FROM product p JOIN supplier s ON s.id=p.supplier_id";
            if(active.equals("true")||active.equals("false")){filter+=" AND p.enabled=?";params.add(Boolean.valueOf(active));}
            if(!category.equals("ALL")){filter+=" AND p.category=?";params.add(category);}
            if(lowStock)filter+=" AND p.stock<p.min_stock";
            if(!q.isBlank()){filter+=" AND (LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.sku) LIKE ? ESCAPE '!' OR LOWER(s.name) LIKE ? ESCAPE '!')";for(int i=0;i<3;i++)params.add(pattern(q));}
        }else if(kind.equals("requests")){
            select=ProcurementService.REQUEST_SELECT.substring(0,ProcurementService.REQUEST_SELECT.indexOf("FROM purchase_request"));
            from=" FROM purchase_request r JOIN product p ON p.id=r.product_id JOIN app_user u ON u.id=r.applicant_id";
            if(a.is("EMPLOYEE")){scope+=" AND r.applicant_id=?";base.add(a.id());}else if(a.is("MANAGER")){scope+=" AND u.department=?";base.add(a.department());}
            if(!status.equals("ALL")){filter+=" AND (CASE WHEN r.lifecycle_state=\'ACTIVE\' THEN r.status ELSE r.lifecycle_state END)=?";params.add(status);}
            if(!q.isBlank()){filter+=" AND (LOWER(r.number) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(u.department) LIKE ? ESCAPE '!' OR LOWER(r.reason) LIKE ? ESCAPE '!' OR EXISTS (SELECT 1 FROM request_line l WHERE l.request_id=r.id AND (LOWER(l.product_name) LIKE ? ESCAPE '!' OR LOWER(l.sku) LIKE ? ESCAPE '!')))";for(int i=0;i<6;i++)params.add(pattern(q));}
        }else if(kind.equals("orders")){
            a.require("ADMIN","BUYER","WAREHOUSE");select=ProcurementService.ORDER_SELECT.substring(0,ProcurementService.ORDER_SELECT.indexOf("FROM purchase_order"));
            from=" FROM purchase_order o JOIN purchase_request r ON r.id=o.request_id JOIN product p ON p.id=r.product_id JOIN supplier s ON s.id=o.supplier_id JOIN app_user u ON u.id=o.buyer_id";
            if(!status.equals("ALL")){filter+=" AND (CASE WHEN o.lifecycle_state=\'ACTIVE\' THEN o.fulfillment_status ELSE o.lifecycle_state END)=?";params.add(status);}
            if(!q.isBlank()){filter+=" AND (LOWER(o.number) LIKE ? ESCAPE '!' OR LOWER(s.name) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR EXISTS (SELECT 1 FROM order_line l WHERE l.order_id=o.id AND (LOWER(l.product_name) LIKE ? ESCAPE '!' OR LOWER(l.sku) LIKE ? ESCAPE '!')))";for(int i=0;i<5;i++)params.add(pattern(q));}
        }else throw new BusinessError(404,"列表不存在");
        if(kind.equals("requests")||kind.equals("orders")){
            if(requestType.equals("LEGACY"))scope+=" AND r.request_type IS NULL";
            else if(Set.of("PURCHASE","RESTOCK").contains(requestType)){scope+=" AND r.request_type=?";base.add(requestType);}
        }
        var all=new ArrayList<Object>(base);all.addAll(params);
        long total=db.queryForObject("SELECT COUNT(*)"+from+scope+filter,Long.class,all.toArray());
        page=(int)Math.min(page,Math.max(1,(total+size-1)/size));
        String alias=kind.equals("products")?"p":kind.equals("requests")?"r":"o";
        String sort=kind.equals("products")?"p.id DESC":alias+".created_at DESC,"+alias+".id DESC";
        all.add(size);all.add((long)(page-1)*size);
        var items=db.queryForList(select+from+scope+filter+" ORDER BY "+sort+" LIMIT ? OFFSET ?",all.toArray());
        if(!kind.equals("products"))lines.batch(items,kind.equals("orders"));
        else addReplenishment(a,items.stream().filter(p->((Number)p.get("stock")).intValue()<((Number)p.get("min_stock")).intValue()).toList());
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("total",total);result.put("page",page);result.put("size",size);
        if(kind.equals("products"))result.put("categories",db.queryForList("SELECT DISTINCT category FROM product ORDER BY category",String.class));
        else result.put("counts",db.queryForList("SELECT "+(kind.equals("requests")?"CASE WHEN r.lifecycle_state=\'ACTIVE\' THEN r.status ELSE r.lifecycle_state END":"CASE WHEN o.lifecycle_state=\'ACTIVE\' THEN o.fulfillment_status ELSE o.lifecycle_state END")+" AS status,COUNT(*) AS total"+from+scope+" GROUP BY "+(kind.equals("requests")?"CASE WHEN r.lifecycle_state=\'ACTIVE\' THEN r.status ELSE r.lifecycle_state END":"CASE WHEN o.lifecycle_state=\'ACTIVE\' THEN o.fulfillment_status ELSE o.lifecycle_state END"),base.toArray()));
        return result;
    }
    private Map<String,Object> history(Account actor,String kind,int page,int size,String query,LocalDate fromDate,LocalDate toDate,String operator){
        boolean audit=kind.equals("audit");
        if(audit)actor.require("ADMIN");else actor.require("ADMIN","BUYER","WAREHOUSE");
        if(fromDate!=null&&toDate!=null&&fromDate.isAfter(toDate))throw new BusinessError(400,"开始日期不能晚于结束日期");
        String select=audit?"SELECT h.*,u.display_name AS actor_name":"SELECT h.*,p.name AS product_name,p.sku,p.unit,u.display_name AS operator_name";
        String from=audit?" FROM audit_log h JOIN app_user u ON u.id=h.actor_id":
            " FROM stock_movement h JOIN product p ON p.id=h.product_id JOIN app_user u ON u.id=h.operator_id";
        String where=" WHERE 1=1";
        var args=new ArrayList<Object>();
        if(fromDate!=null){where+=" AND h.created_at>=?";args.add(fromDate.atStartOfDay());}
        if(toDate!=null){where+=" AND h.created_at<?";args.add(toDate.plusDays(1).atStartOfDay());}
        if(!operator.isBlank()){
            where+=" AND (LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(u.username) LIKE ? ESCAPE '!')";
            args.add(pattern(operator));args.add(pattern(operator));
        }
        if(!query.isBlank()){
            where+=audit?" AND (LOWER(h.target) LIKE ? ESCAPE '!' OR LOWER(h.action) LIKE ? ESCAPE '!' OR LOWER(h.detail) LIKE ? ESCAPE '!')":
                " AND (LOWER(h.reference) LIKE ? ESCAPE '!' OR LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.sku) LIKE ? ESCAPE '!')";
            for(int i=0;i<3;i++)args.add(pattern(query));
        }
        long total=db.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        page=(int)Math.min(page,Math.max(1,(total+size-1)/size));
        args.add(size);args.add((long)(page-1)*size);
        var items=db.queryForList(select+from+where+" ORDER BY h.created_at DESC,h.id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    void addReplenishment(Account actor,List<Map<String,Object>> products){
        if(products.isEmpty())return;
        String marks=String.join(",",Collections.nCopies(products.size(),"?"));
        var args=new ArrayList<Object>();
        for(var product:products)args.add(product.get("id"));
        String scope="";
        if(actor.is("EMPLOYEE")){scope=" AND r.applicant_id=?";args.add(actor.id());}
        else if(actor.is("MANAGER")){scope=" AND u.department=?";args.add(actor.department());}
        String requestJoin=" JOIN app_user u ON u.id=r.applicant_id WHERE l.product_id IN ("+marks+")"+scope;
        var requests=db.queryForList("""
            SELECT l.product_id,r.status,SUM(l.quantity) AS quantity
            FROM request_line l JOIN purchase_request r ON r.id=l.request_id
            """+requestJoin+" AND r.lifecycle_state='ACTIVE' AND r.status IN ('PENDING','APPROVED') GROUP BY l.product_id,r.status",args.toArray());
        var orders=db.queryForList("""
            SELECT l.product_id,'INBOUND' AS status,SUM(l.quantity-l.received_quantity+l.returned_quantity) AS quantity
            FROM order_line l JOIN purchase_order o ON o.id=l.order_id
            JOIN purchase_request r ON r.id=o.request_id
            """+requestJoin+" AND o.lifecycle_state='ACTIVE' AND l.quantity>l.received_quantity-l.returned_quantity GROUP BY l.product_id",args.toArray());
        requests.addAll(orders);
        for(var product:products)product.put("replenishment",requests.stream()
            .filter(row->row.get("product_id").equals(product.get("id"))).toList());
    }
    static String pattern(String q){return "%"+q.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
