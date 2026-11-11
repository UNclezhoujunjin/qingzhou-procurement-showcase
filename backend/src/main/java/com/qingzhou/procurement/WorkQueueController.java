package com.qingzhou.procurement;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkQueueController {
    private final JdbcTemplate db;
    private final Accounts accounts;
    WorkQueueController(JdbcTemplate db, Accounts accounts) {
        this.db=db;
        this.accounts=accounts;
    }

    @GetMapping("/api/work-queue")
    Map<String,List<Map<String,Object>>> queue(Authentication auth,@RequestParam(defaultValue="1") int page) {
        long offset=(Math.max(1L,page)-1)*5;
        var actor=accounts.current(auth);
        var tasks=new LinkedHashMap<String,List<Map<String,Object>>>();
        String requests="""
            SELECT r.id,r.number,r.request_type,r.updated_at AS waiting_since,p.name AS label,COUNT(*) OVER() AS task_total
            FROM purchase_request r JOIN product p ON p.id=r.product_id
            JOIN app_user u ON u.id=r.applicant_id
            WHERE r.lifecycle_state='ACTIVE'
            """;
        String oldest=" ORDER BY r.updated_at,r.id LIMIT 5 OFFSET ?";
        if(actor.is("ADMIN")||actor.is("MANAGER")){
            String approval=" AND r.status='PENDING' AND r.applicant_id<>? AND (r.approver_id IS NULL OR r.approver_id=?)";
            tasks.put("approval",actor.is("MANAGER")
                ?db.queryForList(requests+approval+" AND u.department=?"+oldest,actor.id(),actor.id(),actor.department(),offset)
                :db.queryForList(requests+approval+oldest,actor.id(),actor.id(),offset));
        }
        if(actor.is("ADMIN")||actor.is("BUYER"))
            tasks.put("purchase",db.queryForList(requests+" AND r.status='APPROVED'"+oldest,offset));
        if(actor.is("ADMIN")||actor.is("WAREHOUSE"))
            tasks.put("receipt",db.queryForList("""
                SELECT o.id,o.number,r.request_type,o.created_at AS waiting_since,s.name AS label,COUNT(*) OVER() AS task_total
                FROM purchase_order o JOIN purchase_request r ON r.id=o.request_id JOIN supplier s ON s.id=o.supplier_id
                WHERE o.lifecycle_state='ACTIVE' AND EXISTS (
                    SELECT 1 FROM order_line l WHERE l.order_id=o.id
                    AND l.quantity>l.received_quantity-l.returned_quantity
                ) ORDER BY o.created_at,o.id LIMIT 5 OFFSET ?
                """,offset));
        return tasks;
    }
}
