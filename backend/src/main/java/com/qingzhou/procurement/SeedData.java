package com.qingzhou.procurement;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SeedData implements CommandLineRunner {
    private final LineItems lines;
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final boolean demo;
    private final String bootstrap;
    SeedData(JdbcTemplate db, PasswordEncoder passwords, LineItems lines, @Value("${app.demo}") boolean demo,
             @Value("${app.bootstrap-password}") String bootstrap) {
        this.db=db; this.passwords=passwords; this.lines=lines; this.demo=demo; this.bootstrap=bootstrap;
    }
    @Override @Transactional
    public void run(String... args) {
        if (db.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) > 0) return;
        if (!demo) {
            if (bootstrap.length()<8 || bootstrap.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
                throw new IllegalStateException("首次启动请设置至少 8 字符且不超过 72 字节的 BOOTSTRAP_ADMIN_PASSWORD");
            user("admin", bootstrap, "系统管理员", "管理中心", "ADMIN");
            return;
        }
        String password="Qingzhou@2026";
        user("admin",password,"陈予安","管理中心","ADMIN");
        user("employee",password,"林小满","产品研发部","EMPLOYEE");
        user("manager",password,"周亦舟","产品研发部","MANAGER");
        user("buyer",password,"许知远","采购部","BUYER");
        user("warehouse",password,"沈禾","仓储部","WAREHOUSE");
        user("employee2",password,"方时雨","市场运营部","EMPLOYEE");
        db.update("INSERT INTO supplier(name,contact,phone) VALUES (?,?,?)","杭州智联办公设备有限公司","王经理","0571-88001234");
        db.update("INSERT INTO supplier(name,contact,phone) VALUES (?,?,?)","上海禾木文具有限公司","李经理","021-66005678");
        db.update("INSERT INTO supplier(name,contact,phone) VALUES (?,?,?)","深圳云端数码有限公司","张经理","0755-86009012");
        product("IT-001","联想 ThinkBook 14 笔记本","数码设备","台",5299,5,12,1);
        product("IT-002","戴尔 27 英寸显示器","数码设备","台",1599,8,6,1);
        product("OF-001","得力 A4 复印纸 · 5 包装","办公耗材","箱",125,20,46,2);
        product("OF-002","罗技无线键鼠套装","办公设备","套",249,10,8,3);
        product("OF-003","人体工学办公椅","办公设备","把",899,5,18,1);
        product("OF-004","斑马中性笔 · 12 支装","办公耗材","盒",36,15,62,2);
        product("IT-003","三星 T7 移动固态硬盘 1TB","数码设备","块",699,5,3,3);
        product("OF-005","飞利浦桌面护眼灯","办公设备","盏",299,5,16,1);
        for (var p : db.queryForList("SELECT id,stock FROM product"))
            db.update("INSERT INTO stock_movement(id,product_id,type,quantity,balance,reference,operator_id) VALUES (?,?,'OPENING',?,?,?,1)",
                UUID.randomUUID().toString(),p.get("id"),p.get("stock"),p.get("stock"),"期初建账");
        request("demo-request-1","PR-202610-001",2,1,3,"新增研发工位设备配置","PENDING",-3);
        request("demo-request-2","PR-202610-002",2,3,10,"十一月部门办公耗材补充","PENDING",-2);
        request("demo-request-3","PR-202610-003",6,5,4,"市场团队新增工位","PENDING",-1);
        request("demo-request-4","PR-202610-004",2,2,6,"设计协作显示器升级","APPROVED",-5);
        request("demo-request-5","PR-202610-005",2,4,12,"新同事入职外设配置","ORDERED",-6);
        request("demo-request-6","PR-202610-006",6,8,3,"公共办公区照明补充","REJECTED",-4);
        db.update("INSERT INTO purchase_order(id,number,request_id,supplier_id,status,buyer_id) VALUES ('demo-order-1','PO-202610-001','demo-request-5',3,'OPEN',4)");
        lines.backfillLegacySeed();
        db.update("INSERT INTO audit_log(actor_id,action,target,detail) VALUES (1,'初始化演示数据','系统','已建立商品、采购申请和期初库存')");
    }
    private void user(String username,String password,String name,String department,String role) {
        db.update("INSERT INTO app_user(username,password_hash,display_name,department,role) VALUES (?,?,?,?,?)",username,passwords.encode(password),name,department,role);
        db.update("INSERT INTO mutation_lock(actor_id) SELECT id FROM app_user WHERE username=?",username);
    }
    private void product(String sku,String name,String category,String unit,int price,int min,int stock,int supplier) {
        db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,stock,supplier_id) VALUES (?,?,?,?,?,?,?,?)",sku,name,category,unit,price,min,stock,supplier);
    }
    private void request(String id,String number,int applicant,int product,int quantity,String reason,String status,int days) {
        db.update("INSERT INTO purchase_request(id,number,applicant_id,product_id,quantity,unit_price,reason,status,created_at) SELECT ?,?,?,?,?,price,?,?,? FROM product WHERE id=?",
            id,number,applicant,product,quantity,reason,status,java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusDays(days)),product);
    }
}
