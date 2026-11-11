package com.qingzhou.procurement;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
class WorkController {
    private final Accounts accounts;private final FulfillmentService fulfillment;private final InventoryOperations inventory;
    private final Notifications notifications;private final PeopleService people;
    WorkController(Accounts accounts,FulfillmentService fulfillment,InventoryOperations inventory,Notifications notifications,PeopleService people) {
        this.accounts=accounts;this.fulfillment=fulfillment;this.inventory=inventory;this.notifications=notifications;this.people=people;
    }
    record LineQuantity(@NotBlank @Size(max=36) String orderLineId,@Min(1) @Max(100000) int quantity){}
    record FulfillmentInput(@NotEmpty @Size(max=100) List<@NotNull @Valid LineQuantity> lines,@Size(max=300) String reason,@NotNull @Min(0) Integer version){}
    record ReturnInput(@Min(1) @Max(100000) int quantity,@NotBlank @Size(max=100) String reason){}
    record CountLineInput(@Positive long productId,@Min(0) @Max(100000000) int countedQuantity,@Min(0) int stockVersion){}
    record CountInput(@NotEmpty @Size(max=100) List<@NotNull @Valid CountLineInput> lines,@NotBlank @Size(max=300) String reason){}
    record ProfileInput(@NotBlank @Size(max=64) String name,@NotNull @Email @Size(max=160) String email,@NotNull @Size(max=32) String phone,@NotNull @Min(0) Integer version){}
    record PasswordInput(@NotBlank @Size(max=256) String currentPassword,@NotBlank @Size(min=8,max=64) String newPassword){}

    @PostMapping("/orders/{id}/receive") Map<String,?> receive(Authentication auth,@PathVariable String id,
        @Valid @RequestBody(required=false) FulfillmentInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var a=accounts.current(auth);
        if(input==null){fulfillment.receiveAll(a,id);return Map.of("message","已确认整单收货");}
        return fulfillment.receive(a,id,input,key);
    }
    @PostMapping("/orders/{id}/return") Map<String,Object> purchaseReturn(Authentication auth,@PathVariable String id,
        @Valid @RequestBody FulfillmentInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {return fulfillment.purchaseReturn(accounts.current(auth),id,input,key);}
    @GetMapping("/orders/{id}/events") List<Map<String,Object>> events(Authentication auth,@PathVariable String id){return fulfillment.history(accounts.current(auth),id);}
    @GetMapping("/inventory/issues") List<Map<String,Object>> issues(Authentication auth){return inventory.issues(accounts.current(auth));}
    @PostMapping("/inventory/issues/{id}/return") Map<String,Object> issueReturn(Authentication auth,@PathVariable String id,@Valid @RequestBody ReturnInput input,
        @RequestHeader(value="Idempotency-Key",required=false) String key){return inventory.returnIssue(accounts.current(auth),id,input,key);}
    @GetMapping("/inventory/counts") List<Map<String,Object>> counts(Authentication auth){return inventory.counts(accounts.current(auth));}
    @PostMapping("/inventory/counts") Map<String,Object> count(Authentication auth,@Valid @RequestBody CountInput input,
        @RequestHeader(value="Idempotency-Key",required=false) String key){return inventory.count(accounts.current(auth),input,key);}
    @GetMapping("/notifications") Map<String,Object> inbox(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean unread){return notifications.inbox(accounts.current(auth),page,size,unread);}
    @PostMapping("/notifications/read-all") Map<String,String> readAll(Authentication auth){notifications.readAll(accounts.current(auth));return Map.of("message","全部通知已标为已读");}
    @PostMapping("/notifications/{id}/read") Map<String,String> read(Authentication auth,@PathVariable String id){notifications.read(accounts.current(auth),id);return Map.of("message","通知已读");}
    @GetMapping("/profile") Map<String,Object> profile(Authentication auth){return people.profile(accounts.current(auth));}
    @PutMapping("/profile") Map<String,String> profile(Authentication auth,@Valid @RequestBody ProfileInput input,HttpServletRequest request){people.profile(accounts.current(auth),input);return Map.of("message","个人资料已保存");}
    @PostMapping("/profile/password") Map<String,String> password(Authentication auth,@Valid @RequestBody PasswordInput input,HttpServletRequest request){people.password(accounts.current(auth),input);signOut(request);return Map.of("message","密码已更新，请使用新密码登录");}
    private void signOut(HttpServletRequest request){var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();}
}
