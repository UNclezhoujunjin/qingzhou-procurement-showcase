package com.qingzhou.procurement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/assistant")
class InventoryAssistantController {
    private final Accounts accounts;
    private final Writes writes;
    private final BrowseController browse;
    private final InventoryAssistant assistant;

    InventoryAssistantController(Accounts accounts,Writes writes,BrowseController browse,InventoryAssistant assistant){
        this.accounts=accounts;
        this.writes=writes;
        this.browse=browse;
        this.assistant=assistant;
    }

    record Question(@NotNull @Positive Long productId,@NotBlank @Size(max=300) String question){}

    @PostMapping
    Map<String,Object> ask(Authentication authentication,@Valid @RequestBody Question input){
        var actor=accounts.current(authentication);
        var snapshot=snapshot(actor,input.productId());
        var reply=assistant.explain(input.question().strip(),snapshot.facts());
        return Map.of("product",snapshot.product(),"facts",snapshot.facts(),"answer",reply.answer(),"draftReason",reply.draftReason(),"status",reply.status());
    }

    record Snapshot(Map<String,Object> product,Map<String,Object> facts){}

    Snapshot snapshot(Account actor,long productId){
        var product=writes.one("""
            SELECT p.*,s.name AS supplier_name FROM product p JOIN supplier s ON s.id=p.supplier_id
            WHERE p.id=?
            """,productId);
        browse.addReplenishment(actor,List.of(product));
        int stock=((Number)product.get("stock")).intValue();
        int minimum=((Number)product.get("min_stock")).intValue();
        long gap=Math.max(0L,(long)minimum-stock);
        var quantities=new HashMap<String,Long>();
        if(product.get("replenishment") instanceof List<?> progress){
            for(var item:progress){
                if(item instanceof Map<?,?> row&&row.get("quantity") instanceof Number quantity)
                    quantities.put(row.get("status").toString(),quantity.longValue());
            }
        }
        long inbound=quantities.getOrDefault("INBOUND",0L);
        var facts=Map.<String,Object>of(
            "stock",stock,"minimum",minimum,"gap",gap,"inbound",inbound,
            "pending",quantities.getOrDefault("PENDING",0L),"approved",quantities.getOrDefault("APPROVED",0L),
            "suggested",Math.max(0,gap-inbound),"unit",product.get("unit"),"enabled",product.get("enabled"));
        return new Snapshot(product,facts);
    }
}
