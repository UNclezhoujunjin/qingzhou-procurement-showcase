package com.qingzhou.procurement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProcurementService {
    private final ApprovalRouting routing;
    private final JdbcTemplate db;
    private final LineItems lines;
    private final Notifications notifications;
    private final FulfillmentService fulfillment;
    ProcurementService(ApprovalRouting routing,JdbcTemplate db,LineItems lines,Notifications notifications,FulfillmentService fulfillment) { this.routing=routing;this.db=db;this.lines=lines;this.notifications=notifications;this.fulfillment=fulfillment; }
    static final String REQUEST_SELECT = """
        SELECT r.*, p.name AS product_name, p.sku, p.unit, u.display_name AS applicant_name,
          u.department, (SELECT display_name FROM app_user WHERE id=r.approver_id) AS approver_name, (SELECT username FROM app_user WHERE id=r.approver_id) AS approver_username, (r.quantity * r.unit_price) AS amount FROM purchase_request r
        JOIN product p ON p.id=r.product_id JOIN app_user u ON u.id=r.applicant_id
        """;
    static final String ORDER_SELECT = """
        SELECT o.*, r.number AS request_number, r.request_type, r.product_id, r.quantity, r.unit_price, r.quantity*r.unit_price AS amount,
          p.name AS product_name, p.unit, s.name AS supplier_name, u.display_name AS buyer_name
        FROM purchase_order o JOIN purchase_request r ON r.id=o.request_id
        JOIN product p ON p.id=r.product_id JOIN supplier s ON s.id=o.supplier_id
        JOIN app_user u ON u.id=o.buyer_id
        """;

    List<Map<String,Object>> requests(Account a) {
        List<Map<String,Object>> result;
        if (a.is("EMPLOYEE")) result=db.queryForList(REQUEST_SELECT+" WHERE r.applicant_id=? ORDER BY r.created_at DESC", a.id());
        else if (a.is("MANAGER")) result=db.queryForList(REQUEST_SELECT+" WHERE u.department=? ORDER BY r.created_at DESC", a.department());
        else result=db.queryForList(REQUEST_SELECT+" ORDER BY r.created_at DESC");
        lines.batch(result,false);return result;
    }

    @Transactional
    public Map<String,Object> createRequest(Account a, ApiController.RequestInput input, String key) {
        a.require("EMPLOYEE","MANAGER","ADMIN");
        var items=lines.normalize(input);
        String id=UUID.randomUUID().toString();
        String requestType=input.requestType()==null?"PURCHASE":input.requestType();
        if(!Set.of("PURCHASE","RESTOCK").contains(requestType))throw new BusinessError(400,"申请类型无效");
        String fingerprint=items.toString()+":"+input.reason()+":"+input.approverId();
        if(requestType.equals("RESTOCK"))fingerprint+=":RESTOCK";
        String existing=claim(a,"CREATE_REQUEST",key,fingerprint,id);
        if(existing!=null) return lines.request(one(REQUEST_SELECT+" WHERE r.id=?",existing));
        long approver=routing.validate(a,input.approverId());
        var first=items.get(0);
        var p=one("SELECT * FROM product WHERE id=? FOR UPDATE",first.productId());
        db.update("INSERT INTO purchase_request(id,number,applicant_id,product_id,quantity,unit_price,reason,approver_id,status,request_type) VALUES (?,?,?,?,?,?,?,?,'PENDING',?)",
            id,number("PR",id),a.id(),first.productId(),first.quantity(),p.get("price"),input.reason().strip(),approver,requestType);
        lines.snapshot(id,items);
        audit(a,"提交采购申请",number("PR",id),"共 "+items.size()+" 项商品；"+input.reason());
        routing.notify(a,id,number("PR",id),approver);
        return lines.request(one(REQUEST_SELECT+" WHERE r.id=?",id));
    }

    @Transactional
    public void review(Account a,String id,ApiController.ReviewInput input) {
        a.require("MANAGER","ADMIN");
        var r=one(REQUEST_SELECT+" WHERE r.id=?",id);
        if(!r.get("lifecycle_state").equals("ACTIVE"))throw new BusinessError(409,"申请已撤回或结束，请刷新后处理");
        if(r.get("approver_id")!=null&&((Number)r.get("approver_id")).longValue()!=a.id())throw new BusinessError(403,"此申请已指定其他审批负责人");
        if (a.is("MANAGER") && !a.department().equals(r.get("department"))) throw new BusinessError(403,"只能审批本部门申请");
        if (((Number)r.get("applicant_id")).longValue()==a.id()) throw new BusinessError(403,"不能审批自己的采购申请");
        if (input.decision().equals("REJECTED") && input.note().isBlank()) throw new BusinessError(400,"请填写驳回原因");
        int changed=db.update("UPDATE purchase_request SET status=?,review_note=?,reviewer_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING' AND version=?",
            input.decision(),input.note().strip(),a.id(),id,input.version());
        if(changed!=1) throw new BusinessError(409,"申请内容或状态已变化，请刷新后重新核对再审批");
        notifications.closeApproval(id);
        audit(a,input.decision().equals("APPROVED")?"通过采购审批":"驳回采购申请",r.get("number").toString(),input.note());
        notifications.user(((Number)r.get("applicant_id")).longValue(),input.decision().equals("APPROVED")?"采购申请已通过":"采购申请被驳回",r.get("number")+"；"+input.note(),"requests");
        if(input.decision().equals("APPROVED"))notifications.roles("有已审批申请待下单",r.get("number").toString(),"requests","BUYER","ADMIN");
    }

    @Transactional
    public Map<String,Object> order(Account a,String id) {
        a.require("BUYER","ADMIN");
        // Lock the request before checking its order: repeated/concurrent clicks return the same order.
        var r=one("SELECT * FROM purchase_request WHERE id=? FOR UPDATE",id);
        var existing=db.queryForList(ORDER_SELECT+" WHERE o.request_id=? ORDER BY o.supplier_id",id);
        if(!existing.isEmpty())return orderResult(existing);
        if(!r.get("lifecycle_state").equals("ACTIVE"))throw new BusinessError(409,"申请已撤回或结束");
        if(!r.get("status").equals("APPROVED")) throw new BusinessError(409,"仅已通过审批的申请可以下单");
        var groups=new TreeMap<Long,List<Map<String,Object>>>();
        for(var item:lines.requests(id))groups.computeIfAbsent(((Number)item.get("supplier_id")).longValue(),x->new ArrayList<>()).add(item);
        if(groups.isEmpty())throw new BusinessError(409,"申请缺少商品明细");
        for(var group:groups.entrySet()) {
            String orderId=UUID.randomUUID().toString();
            db.update("INSERT INTO purchase_order(id,number,request_id,supplier_id,status,buyer_id) VALUES (?,?,?,?,'OPEN',?)",orderId,number("PO",orderId),id,group.getKey(),a.id());
            for(var item:group.getValue())db.update("INSERT INTO order_line(id,order_id,request_line_id,product_id,product_name,sku,unit,quantity,unit_price) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),orderId,item.get("id"),item.get("product_id"),item.get("product_name"),item.get("sku"),item.get("unit"),item.get("quantity"),item.get("unit_price"));
            audit(a,"创建采购订单",number("PO",orderId),"关联申请 "+r.get("number"));
        }
        db.update("UPDATE purchase_request SET status='ORDERED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        notifications.roles("采购订单待收货",r.get("number")+" 已生成 "+groups.size()+" 笔供应商订单","orders","WAREHOUSE","ADMIN");
        return orderResult(db.queryForList(ORDER_SELECT+" WHERE o.request_id=? ORDER BY o.supplier_id",id));
    }
    private Map<String,Object> orderResult(List<Map<String,Object>> result) {
        result.forEach(lines::order);var first=new LinkedHashMap<>(result.get(0));first.put("orders",result);return first;
    }

    @Transactional
    public void receive(Account a,String id) {
        fulfillment.receiveAll(a,id);
    }

    @Transactional
    public Map<String,Object> issue(Account a,ApiController.IssueInput input,String key) {
        a.require("WAREHOUSE","ADMIN");
        String id=UUID.randomUUID().toString();
        String existing=claim(a,"ISSUE",key,input.productId()+":"+input.quantity()+":"+input.reason(),id);
        if(existing!=null) return one("SELECT * FROM stock_movement WHERE id=?",existing);
        // The predicate and decrement run in one SQL statement under a row lock.
        var before=one("SELECT * FROM product WHERE id=? FOR UPDATE",input.productId());
        int changed=db.update("UPDATE product SET stock=stock-?,stock_version=stock_version+1 WHERE id=? AND stock>=?",input.quantity(),input.productId(),input.quantity());
        if(changed!=1) throw new BusinessError(409,"可用库存不足或商品不存在，请刷新库存");
        int balance=db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,input.productId());
        db.update("INSERT INTO stock_movement(id,product_id,type,quantity,balance,reference,operator_id,business_type) VALUES (?,?,'OUT',?,?,?,?,'ISSUE')",
            id,input.productId(),-input.quantity(),balance,input.reason().strip(),a.id());
        audit(a,"领用出库",String.valueOf(input.productId()),input.reason()+"；数量 "+input.quantity()+"，结存 "+balance);
        notifications.lowStock(before,((Number)before.get("stock")).intValue(),balance);
        return one("SELECT * FROM stock_movement WHERE id=?",id);
    }

    @Transactional
    public void product(Account a,ApiController.ProductInput p) {
        a.require("BUYER","ADMIN");
        one("SELECT id FROM supplier WHERE id=?",p.supplierId());
        db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id) VALUES (?,?,?,?,?,?,?)",
            p.sku().strip(),p.name().strip(),p.category(),p.unit().strip(),p.price(),p.minStock(),p.supplierId());
        audit(a,"新增商品",p.sku(),p.name());
    }

    @Transactional
    public void supplier(Account a,ApiController.SupplierInput s) {
        a.require("BUYER","ADMIN");
        db.update("INSERT INTO supplier(name,contact,phone) VALUES (?,?,?)",s.name().strip(),s.contact().strip(),s.phone().strip());
        audit(a,"新增供应商",s.name(),s.contact());
    }

    @Transactional
    public void updateProduct(Account a,long id,ApiController.ProductUpdateInput input) {
        a.require("BUYER","ADMIN");
        var old=one("SELECT * FROM product WHERE id=? FOR UPDATE",id);
        requireVersion(old,input.version());
        var p=input.data();
        one("SELECT id FROM supplier WHERE id=?",p.supplierId());
        if(!old.get("unit").equals(p.unit().strip()) &&
            (db.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE product_id=?",Long.class,id)>0 ||
             db.queryForObject("SELECT COUNT(*) FROM request_line WHERE product_id=?",Long.class,id)>0 ||
             db.queryForObject("SELECT COUNT(*) FROM stock_count_line WHERE product_id=?",Long.class,id)>0))
            throw new BusinessError(409,"商品已有业务记录，不能修改计量单位；请创建新的商品档案");
        db.update("UPDATE product SET sku=?,name=?,category=?,unit=?,price=?,min_stock=?,supplier_id=?,version=version+1 WHERE id=?",
            p.sku().strip(),p.name().strip(),p.category().strip(),p.unit().strip(),p.price(),p.minStock(),p.supplierId(),id);
        audit(a,"编辑商品档案",p.sku(),"名称："+old.get("name")+" → "+p.name()+"；单价："+old.get("price")+" → "+p.price()+"；安全库存："+old.get("min_stock")+" → "+p.minStock());
    }

    @Transactional
    public void updateSupplier(Account a,long id,ApiController.SupplierUpdateInput input) {
        a.require("BUYER","ADMIN");
        var old=one("SELECT * FROM supplier WHERE id=? FOR UPDATE",id);
        requireVersion(old,input.version());
        var s=input.data();
        db.update("UPDATE supplier SET name=?,contact=?,phone=?,version=version+1 WHERE id=?",s.name().strip(),s.contact().strip(),s.phone().strip(),id);
        audit(a,"编辑供应商",s.name(),"原名称："+old.get("name")+"；联系人："+s.contact()+"；电话："+s.phone());
    }

    @Transactional
    public Map<String,Object> updateRequest(Account a,String id,ApiController.RequestUpdateInput input) {
        a.require("EMPLOYEE","MANAGER","ADMIN");
        var old=one("SELECT * FROM purchase_request WHERE id=? FOR UPDATE",id);
        if(((Number)old.get("applicant_id")).longValue()!=a.id()) throw new BusinessError(403,"只能修改自己提交的采购申请");
        if(!List.of("PENDING","REJECTED").contains(old.get("status"))) throw new BusinessError(409,"仅待审批或已驳回的申请可以修改");
        requireVersion(old,input.version());
        var r=input.data();long approver=routing.validate(a,r.approverId());var items=lines.normalize(r);var first=items.get(0);
        var product=one("SELECT * FROM product WHERE id=? FOR UPDATE",first.productId());
        db.update("DELETE FROM request_line WHERE request_id=?",id);
        db.update("UPDATE purchase_request SET product_id=?,quantity=?,unit_price=?,reason=?,approver_id=?,status='PENDING',lifecycle_state='ACTIVE',reviewer_id=NULL,review_note=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            first.productId(),first.quantity(),product.get("price"),r.reason().strip(),approver,id);
        lines.snapshot(id,items);
        audit(a,old.get("status").equals("REJECTED")?"修改并重新提交":"修改采购申请",old.get("number").toString(),
            "原商品 #"+old.get("product_id")+" × "+old.get("quantity")+"；调整为 "+items.size()+" 项商品");
        routing.notify(a,id,old.get("number").toString(),approver);
        return lines.request(one(REQUEST_SELECT+" WHERE r.id=?",id));
    }

    @Transactional
    public void updateUser(Account a,long id,ApiController.UserUpdateInput input) {
        a.require("ADMIN");
        // Serialize administrator changes so concurrent demotions cannot remove the last administrator.
        one("SELECT id FROM administration_lock WHERE id=1 FOR UPDATE");
        if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND role='ADMIN' AND enabled=TRUE AND deleted=FALSE",Long.class,a.id())==0)throw new BusinessError(401,"管理员账号状态已变化，请重新登录");
        var old=one("SELECT * FROM app_user WHERE id=? FOR UPDATE",id);
        if(Boolean.TRUE.equals(old.get("deleted")))throw new BusinessError(404,"成员已删除，不能编辑或重新启用");
        requireVersion(old,input.version());
        if(a.id()==id && (!input.enabled() || !input.role().equals("ADMIN"))) throw new BusinessError(409,"不能停用自己或移除自己的管理员角色");
        String department=input.department().strip();
        var affected=db.queryForList("""
            SELECT r.number FROM purchase_request r JOIN app_user u ON u.id=r.applicant_id
            LEFT JOIN app_user approver ON approver.id=r.approver_id
            WHERE r.status='PENDING' AND r.lifecycle_state='ACTIVE' AND (
              (r.approver_id=? AND (NOT ? OR (?<>'ADMIN' AND (?<>'MANAGER' OR u.department<>?))))
              OR (r.applicant_id=? AND approver.role='MANAGER' AND u.department<>? AND approver.department<>?)
            ) ORDER BY r.created_at LIMIT 5
            """,String.class,id,input.enabled(),input.role(),input.role(),department,id,department,department);
        if(!affected.isEmpty())throw new BusinessError(409,"成员变更会影响待审批申请，请先改派负责人："+String.join("、",affected));
        if(old.get("role").equals("ADMIN") && Boolean.TRUE.equals(old.get("enabled")) &&
            (!input.enabled() || !input.role().equals("ADMIN")) &&
            db.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND id<>?",Long.class,id)==0)
            throw new BusinessError(409,"至少需要保留一位启用的管理员");
        db.update("UPDATE app_user SET display_name=?,department=?,role=?,enabled=?,version=version+1,auth_epoch=auth_epoch+1 WHERE id=?",
            input.name().strip(),input.department().strip(),input.role(),input.enabled(),id);
        audit(a,"编辑团队成员",old.get("username").toString(),"姓名："+input.name()+"；部门："+old.get("department")+" → "+input.department()+"；角色："+old.get("role")+" → "+input.role()+"；"+(input.enabled()?"启用":"停用"));
    }

    private void requireVersion(Map<String,Object> record,int expected) {
        if(((Number)record.get("version")).intValue()!=expected) throw new BusinessError(409,"记录已被其他操作更新，请刷新后再编辑");
    }

    List<Map<String,Object>> products() {
        return db.queryForList("SELECT p.*,s.name AS supplier_name FROM product p JOIN supplier s ON s.id=p.supplier_id ORDER BY p.id");
    }
    Map<String,Object> overview(Account a) {
        String scope=a.is("EMPLOYEE")?" WHERE r.applicant_id=?":a.is("MANAGER")?" WHERE u.department=?":" WHERE 1=1";
        Object[] args=a.is("EMPLOYEE")?new Object[]{a.id()}:a.is("MANAGER")?new Object[]{a.department()}:new Object[]{};
        String from=" FROM purchase_request r JOIN app_user u ON u.id=r.applicant_id";
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("pending",db.queryForObject("SELECT COUNT(*)"+from+scope+" AND r.status='PENDING' AND r.lifecycle_state='ACTIVE'",Long.class,args));
        result.put("requests",db.queryForObject("SELECT COUNT(*)"+from+scope,Long.class,args));
        result.put("amount",db.queryForObject("SELECT COALESCE(SUM(l.quantity*l.unit_price),0)"+from+" JOIN request_line l ON l.request_id=r.id"+scope+" AND r.status<>'REJECTED' AND r.lifecycle_state<>'WITHDRAWN'",BigDecimal.class,args));
        result.put("counts",db.queryForList("SELECT CASE WHEN r.lifecycle_state=\'ACTIVE\' THEN r.status ELSE r.lifecycle_state END AS status,COUNT(*) AS total"+from+scope+" GROUP BY r.status,r.lifecycle_state",args));
        result.put("categories",db.queryForList("SELECT p.category AS name,SUM(l.quantity*l.unit_price) AS amount"+from+" JOIN request_line l ON l.request_id=r.id JOIN product p ON p.id=l.product_id"+scope+" AND r.status<>'REJECTED' AND r.lifecycle_state<>'WITHDRAWN' GROUP BY p.category",args));
        result.put("lowStock",db.queryForObject("SELECT COUNT(*) FROM product WHERE stock<min_stock",Long.class));
        result.put("openOrders",a.is("ADMIN","BUYER","WAREHOUSE") ? db.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE status='OPEN' AND lifecycle_state='ACTIVE'",Long.class) : null);
        result.put("stockValue",a.is("ADMIN","WAREHOUSE","BUYER") ? db.queryForObject("SELECT COALESCE(SUM(stock*price),0) FROM product",BigDecimal.class) : null);
        return result;
    }
    List<Map<String,Object>> orders(Account a) {
        a.require("ADMIN","BUYER","WAREHOUSE");var result=db.queryForList(ORDER_SELECT+" ORDER BY o.created_at DESC,o.id");lines.batch(result,true);return result;
    }
    List<Map<String,Object>> movements(Account a) {
        a.require("ADMIN","WAREHOUSE","BUYER");
        return db.queryForList("SELECT m.*,p.name AS product_name,p.sku,p.unit,u.display_name AS operator_name FROM stock_movement m JOIN product p ON p.id=m.product_id JOIN app_user u ON u.id=m.operator_id ORDER BY m.created_at DESC,m.id DESC LIMIT 500");
    }
    List<Map<String,Object>> audit(Account a) {
        a.require("ADMIN");
        return db.queryForList("SELECT l.*,u.display_name AS actor_name,u.department FROM audit_log l JOIN app_user u ON u.id=l.actor_id ORDER BY l.created_at DESC,l.id DESC LIMIT 500");
    }
    Map<String,Object> one(String sql,Object... args) {
        var list=db.queryForList(sql,args);
        if(list.isEmpty()) throw new BusinessError(404,"记录不存在");
        return list.get(0);
    }
    private void movement(Account a,Object product,String type,int quantity,int balance,String reference) {
        db.update("INSERT INTO stock_movement(id,product_id,type,quantity,balance,reference,operator_id) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),product,type,quantity,balance,reference,a.id());
    }
    private void audit(Account a,String action,String target,String detail) {
        db.update("INSERT INTO audit_log(actor_id,action,target,detail) VALUES (?,?,?,?)",a.id(),action,target,Notifications.clip(detail,500));
    }
    private static String number(String prefix,String id) {
        return prefix+"-"+id.replace("-","").toUpperCase(Locale.ROOT);
    }
    private String claim(Account a,String operation,String key,String payload,String resource) {
        if(key==null || !key.matches("[a-zA-Z0-9_-]{8,80}")) throw new BusinessError(400,"请提供有效的 Idempotency-Key");
        // Serializes only this account's writes, including its first use of a new key.
        one("SELECT actor_id FROM mutation_lock WHERE actor_id=? FOR UPDATE",a.id());
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        var rows=db.queryForList("SELECT * FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=?",a.id(),operation,key);
        if(!rows.isEmpty()) {
            if(!hash.equals(rows.get(0).get("fingerprint"))) throw new BusinessError(409,"重复请求标识不能用于不同的操作内容");
            return rows.get(0).get("resource_id").toString();
        }
        db.update("INSERT INTO idempotency_record(actor_id,operation,request_key,fingerprint,resource_id) VALUES (?,?,?,?,?)",a.id(),operation,key,hash,resource);
        return null;
    }
}
