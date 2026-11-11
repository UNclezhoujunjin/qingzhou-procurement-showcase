package com.qingzhou.procurement;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/products/import")
class ProductImportController {
 private final Accounts accounts;private final Writes writes;private final ProcurementService service;private final Validator validator;
 ProductImportController(Accounts accounts,Writes writes,ProcurementService service,Validator validator){this.accounts=accounts;this.writes=writes;this.service=service;this.validator=validator;}
 record Batch(List<ApiController.ProductInput> rows){}
 @PostMapping("/preview") Map<String,Object> preview(Authentication auth,@RequestBody Batch batch){var a=accounts.current(auth);a.require("ADMIN","BUYER");return check(batch);}
 @PostMapping @Transactional Map<String,Object> insert(Authentication auth,@RequestBody Batch batch,@RequestHeader("Idempotency-Key") String key){
  var a=accounts.current(auth);a.require("ADMIN","BUYER");bounded(batch);
  if(writes.claim(a,"IMPORT_PRODUCTS",key,batch.toString(),key)!=null)return Map.of("imported",batch.rows().size(),"message","该批商品已导入");
  var result=check(batch);if(!Boolean.TRUE.equals(result.get("valid")))throw new BusinessError(400,"商品校验未通过，请先预检查并修正错误行");
  for(var row:batch.rows())service.product(a,row);
  writes.audit(a,"批量导入商品",key,"新增 "+batch.rows().size()+" 项商品；原有资料未覆盖，初始库存为 0");
  return Map.of("imported",batch.rows().size(),"message","商品批量导入成功");
 }
 private static void bounded(Batch batch){if(batch==null||batch.rows()==null||batch.rows().isEmpty()||batch.rows().size()>200)throw new BusinessError(400,"每次导入 1～200 行商品");}
 private Map<String,Object> check(Batch batch){
  bounded(batch);var errors=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
  for(int i=0;i<batch.rows().size();i++){
   var row=batch.rows().get(i);var messages=new ArrayList<String>();
   if(row==null)messages.add("商品行不能为空");else{
    validator.validate(row).forEach(v->messages.add(v.getPropertyPath()+"："+v.getMessage()));
    if(row.sku()!=null){String sku=row.sku().strip();if(!seen.add(sku))messages.add("文件内 SKU 重复");if(writes.db.queryForObject("SELECT COUNT(*) FROM product WHERE sku=?",Integer.class,sku)>0)messages.add("SKU 已存在，不会覆盖原商品");}
    if(writes.db.queryForObject("SELECT COUNT(*) FROM supplier WHERE id=?",Integer.class,row.supplierId())==0)messages.add("供应商 ID 不存在");
   }
   if(!messages.isEmpty())errors.add(Map.of("row",i+2,"messages",messages));
  }
  return Map.of("valid",errors.isEmpty(),"total",batch.rows().size(),"errors",errors);
 }
}
