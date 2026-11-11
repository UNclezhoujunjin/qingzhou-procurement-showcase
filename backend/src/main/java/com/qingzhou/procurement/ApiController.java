package com.qingzhou.procurement;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private final Writes writes;
    private final Accounts accounts;
    private final ProcurementService service;
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final boolean demo;
    private final String runtimeMode;
    ApiController(Writes writes,Accounts accounts,ProcurementService service,JdbcTemplate db,PasswordEncoder passwords,@org.springframework.beans.factory.annotation.Value("${app.demo}") boolean demo,@org.springframework.beans.factory.annotation.Value("${app.runtime-mode:development}") String runtimeMode) { this.writes=writes;this.accounts=accounts;this.service=service;this.db=db;this.passwords=passwords;this.demo=demo;this.runtimeMode=runtimeMode; }
    record RequestLineInput(@Positive long productId,@Min(1) @Max(100000) int quantity) {}
    record RequestInput(Long productId,Integer quantity,@NotBlank @Size(max=500) String reason,
        @Size(max=100) List<@NotNull @Valid RequestLineInput> lines,@NotNull @Positive Long approverId,
        @Pattern(regexp="PURCHASE|RESTOCK") String requestType) {
        RequestInput(Long productId,Integer quantity,String reason,List<RequestLineInput> lines,Long approverId){this(productId,quantity,reason,lines,approverId,null);}
        RequestInput(Long productId,Integer quantity,String reason,List<RequestLineInput> lines){this(productId,quantity,reason,lines,1L);}
        RequestInput(long productId,int quantity,String reason){this(productId,quantity,reason,null,1L);}
    }
    record ReviewInput(@NotNull @Pattern(regexp="APPROVED|REJECTED") String decision,@NotNull @Size(max=500) String note,@NotNull @Min(0) Integer version) {}
    record RequestUpdateInput(@NotNull @Valid RequestInput data,@NotNull @Min(0) Integer version) {}
    record ProductUpdateInput(@NotNull @Valid ProductInput data,@NotNull @Min(0) Integer version) {}
    record SupplierUpdateInput(@NotNull @Valid SupplierInput data,@NotNull @Min(0) Integer version) {}
    record UserUpdateInput(@NotBlank @Size(max=64) String name,@NotBlank @Size(max=64) String department,
        @NotNull @Pattern(regexp="ADMIN|EMPLOYEE|MANAGER|BUYER|WAREHOUSE") String role,@NotNull Boolean enabled,@NotNull @Min(0) Integer version) {}
    record IssueInput(@Positive long productId,@Min(1) @Max(100000) int quantity,@NotBlank @Size(max=100) String reason) {}
    record ProductInput(@NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String sku,
        @NotBlank @Size(max=120) String name,@NotBlank @Size(max=40) String category,
        @NotBlank @Size(max=16) String unit,@NotNull @DecimalMin("0.01") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) BigDecimal price,
        @Min(0) @Max(100000) int minStock,@Positive long supplierId) {}
    record SupplierInput(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=64) String contact,@NotBlank @Size(max=32) String phone) {}
    record UserInput(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{3,64}") String username,
        @NotBlank @Size(max=64) String name,@NotBlank @Size(max=64) String department,
        @NotNull @Pattern(regexp="ADMIN|EMPLOYEE|MANAGER|BUYER|WAREHOUSE") String role,
        @NotBlank @Size(min=8,max=64) String password) {}

    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/auth/config") Map<String,Object> config() { return Map.of("demo",demo,"runtimeMode",runtimeMode,"application","qingzhou-procurement"); }
    @GetMapping("/auth/me") Account me(Authentication auth) { return accounts.current(auth); }
    @GetMapping("/overview") Map<String,Object> overview(Authentication auth) { return service.overview(accounts.current(auth)); }
    @GetMapping("/products") List<Map<String,Object>> products() { return service.products(); }
    @PostMapping("/products") @org.springframework.transaction.annotation.Transactional
    Map<String,String> product(Authentication auth,@Valid @RequestBody ProductInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var a=accounts.current(auth);a.require("ADMIN","BUYER");
        if(key==null||writes.claim(a,"CREATE_PRODUCT",key,input.toString(),input.sku())==null)service.product(a,input);
        return Map.of("message","商品已创建");
    }
    @PutMapping("/products/{id}") Map<String,String> updateProduct(Authentication auth,@PathVariable long id,@Valid @RequestBody ProductUpdateInput input) {
        service.updateProduct(accounts.current(auth),id,input);return Map.of("message","商品资料已保存");
    }
    @GetMapping("/suppliers") List<Map<String,Object>> suppliers(Authentication auth) {
        accounts.current(auth).require("ADMIN","BUYER"); return db.queryForList("SELECT s.*, (SELECT COUNT(*) FROM product p WHERE p.supplier_id=s.id) AS product_count FROM supplier s ORDER BY s.id");
    }
    @PostMapping("/suppliers") @org.springframework.transaction.annotation.Transactional
    Map<String,String> supplier(Authentication auth,@Valid @RequestBody SupplierInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var a=accounts.current(auth);a.require("ADMIN","BUYER");
        if(key==null||writes.claim(a,"CREATE_SUPPLIER",key,input.toString(),input.name())==null)service.supplier(a,input);
        return Map.of("message","供应商已创建");
    }
    @PutMapping("/suppliers/{id}") Map<String,String> updateSupplier(Authentication auth,@PathVariable long id,@Valid @RequestBody SupplierUpdateInput input) {
        service.updateSupplier(accounts.current(auth),id,input);return Map.of("message","供应商资料已保存");
    }
    @GetMapping("/requests") List<Map<String,Object>> requests(Authentication auth) { return service.requests(accounts.current(auth)); }
    @PostMapping("/requests") Map<String,Object> create(Authentication auth,@Valid @RequestBody RequestInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) { return service.createRequest(accounts.current(auth),input,key); }
    @PutMapping("/requests/{id}") Map<String,Object> updateRequest(Authentication auth,@PathVariable String id,@Valid @RequestBody RequestUpdateInput input) {
        return service.updateRequest(accounts.current(auth),id,input);
    }
    @PostMapping("/requests/{id}/review") @org.springframework.transaction.annotation.Transactional
    Map<String,String> review(Authentication auth,@PathVariable String id,@Valid @RequestBody ReviewInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var a=accounts.current(auth);a.require("ADMIN","MANAGER");
        if(key==null||writes.claim(a,"REVIEW_REQUEST",key,id+":"+input,id)==null)service.review(a,id,input);
        return Map.of("message","审批已完成");
    }
    @PostMapping("/requests/{id}/order") Map<String,Object> order(Authentication auth,@PathVariable String id) { return service.order(accounts.current(auth),id); }
    @GetMapping("/orders") List<Map<String,Object>> orders(Authentication auth) { return service.orders(accounts.current(auth)); }
    @PostMapping("/inventory/issue") Map<String,Object> issue(Authentication auth,@Valid @RequestBody IssueInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) { return service.issue(accounts.current(auth),input,key); }
    @GetMapping("/movements") List<Map<String,Object>> movements(Authentication auth) { return service.movements(accounts.current(auth)); }
    @GetMapping("/audit") List<Map<String,Object>> audit(Authentication auth) { return service.audit(accounts.current(auth)); }
    @GetMapping("/users") List<Map<String,Object>> users(Authentication auth) {
        accounts.current(auth).require("ADMIN");
        return db.queryForList("SELECT id,username,display_name,department,role,enabled,version FROM app_user WHERE deleted=FALSE ORDER BY id");
    }
    @GetMapping("/users/{id}/pending-approvals") List<Map<String,Object>> pendingApprovals(Authentication auth,@PathVariable long id){
        accounts.current(auth).require("ADMIN");
        return db.queryForList("""
            SELECT id,number,COUNT(*) OVER() AS total FROM purchase_request
            WHERE status='PENDING' AND lifecycle_state='ACTIVE' AND (approver_id=? OR applicant_id=?)
            ORDER BY created_at,id LIMIT 5
            """,id,id);
    }
    @PutMapping("/users/{id}") Map<String,String> updateUser(Authentication auth,@PathVariable long id,@Valid @RequestBody UserUpdateInput input) {
        service.updateUser(accounts.current(auth),id,input);return Map.of("message","成员资料已保存，原登录已失效");
    }
    @PostMapping("/users") @org.springframework.transaction.annotation.Transactional
    Map<String,String> user(Authentication auth,@Valid @RequestBody UserInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        Account actor=accounts.current(auth);actor.require("ADMIN");
        if(key!=null&&writes.claim(actor,"CREATE_USER",key,input.toString(),input.username())!=null)return Map.of("message","成员已创建");
        if(input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new BusinessError(400,"密码的 UTF-8 长度不能超过 72 字节");
        db.update("INSERT INTO app_user(username,password_hash,display_name,department,role) VALUES (?,?,?,?,?)",
            input.username(),passwords.encode(input.password()),input.name().strip(),input.department().strip(),input.role());
        db.update("INSERT INTO mutation_lock(actor_id) SELECT id FROM app_user WHERE username=?",input.username());
        db.update("INSERT INTO audit_log(actor_id,action,target,detail) VALUES (?,'创建团队成员',?,?)",actor.id(),input.username(),input.name()+" / "+input.department()+" / "+input.role());
        return Map.of("message","成员已创建");
    }
}
