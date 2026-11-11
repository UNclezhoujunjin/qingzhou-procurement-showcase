package com.qingzhou.procurement;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class UsabilityPolishTest {
    @Autowired JdbcTemplate db;
    @Autowired Notifications notifications;
    @Autowired ProcurementService procurement;
    @Autowired Accounts accounts;
    @Autowired Writes writes;
    @Autowired InventoryAssistantController inventory;
    @Autowired WorkQueueController queue;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    final Account admin=new Account(1,"admin","管理员","管理中心","ADMIN");

    @Test void agentReportsModelFailuresAndKeepsDirectInventoryAvailable() throws Exception {
        var model=org.mockito.Mockito.mock(InventoryAssistant.class);
        var completion=org.mockito.Mockito.when(model.complete(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap()));
        completion.thenReturn(new InventoryAssistant.Reply("","","timeout"),
            new InventoryAssistant.Reply("not a plan","","ready"),
            new InventoryAssistant.Reply("{\"actions\":[{\"tool\":\"approve\"}]}","","ready"),
            new InventoryAssistant.Reply("{\"actions\":[]}","","ready"));
        var agent=new InventoryAgentController(accounts,writes,model,inventory,queue,json);
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("employee","",List.of());
        for(String expected:List.of("timeout","invalid_reply","invalid_reply","ready")){
            var response=json.valueToTree(agent.ask(auth,new InventoryAgentController.Input("查看办公纸",null,null,null)));
            assertEquals(expected,response.path("status").asText());
            assertTrue(response.path("results").isEmpty());
            var direct=json.valueToTree(agent.ask(auth,new InventoryAgentController.Input("查看低库存商品",null,null,"shortages")));
            assertEquals("direct",direct.path("status").asText());
            assertTrue(direct.path("results").path(0).path("products").isArray());
        }
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(4))
            .complete(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="QINGZHOU_AI_LIVE_EVAL",matches="1")
    void liveInventoryAgentEvaluation() throws Exception {
        String key=System.getenv("QINGZHOU_AI_API_KEY");
        assertTrue(key!=null&&!key.isBlank(),"Set QINGZHOU_AI_API_KEY before running the live evaluation");
        var env=System.getenv();
        var model=new InventoryAssistant(json,key,
            env.getOrDefault("QINGZHOU_AI_ENDPOINT","https://api.siliconflow.cn/v1/chat/completions"),
            env.getOrDefault("QINGZHOU_AI_MODEL","XingChenAGI/Xing4.0-29B"));
        for(String sku:List.of("EVAL-FOLDER-A","EVAL-FOLDER-B")){
            db.update("""
                INSERT INTO product(sku,name,category,unit,price,stock,min_stock,supplier_id)
                SELECT ?,'评测文件夹','演示','件',1,2,10,MIN(id) FROM supplier
                """,sku);
        }
        var agent=new InventoryAgentController(accounts,writes,model,inventory,queue,json);
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("employee","",List.of());
        record Scenario(String id,String question,String tool,java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> valid){}
        var scenarios=List.of(
            new Scenario("stock","EVAL-FOLDER-A 还需要补多少？","inspect",
                result->"EVAL-FOLDER-A".equals(result.path("product").path("sku").asText())&&result.path("facts").path("suggested").asInt()==8),
            new Scenario("shortages","列出低库存商品","shortages",result->result.path("products").isArray()),
            new Scenario("tasks","看看我当前有哪些待办","tasks",result->result.path("tasks").isObject()),
            new Scenario("ambiguous","帮我查一下评测文件夹的库存","inspect",result->result.path("candidates").size()==2));
        var failures=new ArrayList<String>();
        for(var scenario:scenarios){
            if(scenario!=scenarios.get(0))Thread.sleep(1600);
            long start=System.nanoTime();
            var response=json.valueToTree(agent.ask(auth,new InventoryAgentController.Input(scenario.question(),null,null,null)));
            long milliseconds=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            String status=response.path("status").asText();
            var results=response.path("results");
            var result=results.path(0);
            String actualTool=result.path("tool").asText("-");
            boolean passed="ready".equals(status)&&results.size()==1&&scenario.tool().equals(actualTool)&&scenario.valid().test(result);
            String row="%s expected=%s actual=%s status=%s elapsed_ms=%d %s".formatted(
                scenario.id(),scenario.tool(),actualTool,status,milliseconds,passed?"PASS":"FAIL");
            System.out.println("AI evaluation "+row);
            if(!passed)failures.add(row);
        }
        System.out.printf("AI evaluation: %d/%d passed%n",scenarios.size()-failures.size(),scenarios.size());
        assertTrue(failures.isEmpty(),()->String.join("\n",failures));
    }

    @Test void agentUsesModelPlanButOnlyRunsAllowedToolsWithinAccountScope() throws Exception {
        var model=org.mockito.Mockito.mock(InventoryAssistant.class);
        org.mockito.Mockito.when(model.complete(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(new InventoryAssistant.Reply("{\"actions\":[{\"tool\":\"approve\",\"query\":\"anything\"},{\"tool\":\"inspect\"},{\"tool\":\"tasks\"}]}","","ready"));
        var agent=new InventoryAgentController(accounts,writes,model,inventory,queue,json);
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("employee","",List.of());
        var response=json.valueToTree(agent.ask(auth,new InventoryAgentController.Input("它还要补多少？也看看我的待办",1L,"查看这个商品",null)));
        assertEquals(2,response.path("results").size());
        assertEquals(1,response.path("results").path(0).path("product").path("id").asInt());
        assertEquals("tasks",response.path("results").path(1).path("tool").asText());
        assertTrue(response.path("results").path(1).path("tasks").isEmpty());
        mvc.perform(post("/api/inventory/agent").with(csrf()).contentType("application/json").content("{\"question\":\"看待办\",\"tool\":\"tasks\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void auditHistoryFiltersOldRecordsByDateReferenceAndOperator() throws Exception {
        String reference="AUDIT-"+UUID.randomUUID();
        db.update("INSERT INTO audit_log(actor_id,action,target,detail,created_at) VALUES (1,'验证',?,'测试',?)",
            reference,java.time.LocalDateTime.of(2020,6,2,23,59));
        mvc.perform(get("/api/browse/audit").param("q",reference).param("operator","admin").param("fromDate","2020-06-02").param("toDate","2020-06-02").with(user("admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].target").value(reference));
        mvc.perform(get("/api/browse/audit").with(user("employee"))).andExpect(status().isForbidden());
    }

    @Test void onlyOwnerCanDiscardDraftAndStaleTabCannotRecreateIt() throws Exception {
        String id=UUID.randomUUID().toString(), path="/api/drafts/"+id;
        String draft=json.writeValueAsString(Map.of("version",0,"payload","{\"reason\":\"草稿\"}"));
        mvc.perform(put(path).with(user("employee")).with(csrf()).contentType("application/json").content(draft))
            .andExpect(status().isOk());
        for(String actor:List.of("admin","employee","employee"))
            mvc.perform(post(path+"/discard").with(user(actor)).with(csrf()).contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().is(actor.equals("admin")?404:200));
        mvc.perform(put(path).with(user("employee")).with(csrf()).contentType("application/json").content(draft))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/drafts").with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '"+id+"')]").isEmpty());
    }

    @Test void purchaseProgressFollowsTheSameRequestThroughApprovalOrderAndPartialReceipt() throws Exception {
        String sku="FLOW-"+UUID.randomUUID().toString().substring(0,12);
        db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id) SELECT ?,'流程测试','测试','件',1,10,MIN(id) FROM supplier",sku);
        long product=db.queryForObject("SELECT id FROM product WHERE sku=?",Long.class,sku);
        var employee=new Account(2,"employee","员工","产品研发部","EMPLOYEE");
        String request=procurement.createRequest(employee,new ApiController.RequestInput(product,8,"流程验证",null,1L,"RESTOCK"),UUID.randomUUID().toString()).get("id").toString();
        db.update("UPDATE purchase_request SET updated_at=? WHERE id=?",java.time.LocalDateTime.of(2000,1,1,0,0),request);
        mvc.perform(get("/api/browse/requests").param("q","流程验证").param("requestType","RESTOCK").with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(request))
            .andExpect(jsonPath("$.items[0].request_type").value("RESTOCK"));
        mvc.perform(get("/api/browse/requests").param("q","流程验证").param("requestType","PURCHASE").with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/work-queue").with(user("admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.approval[0].id").value(request));
        mvc.perform(get("/api/work-queue").with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.approval").doesNotExist());
        mvc.perform(get("/api/browse/products").param("q",sku).with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].replenishment[0].quantity").value(8));
        db.update("UPDATE purchase_request SET status='APPROVED' WHERE id=?",request);
        mvc.perform(get("/api/work-queue").with(user("buyer")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purchase[0].id").value(request));
        String order=procurement.order(admin,request).get("id").toString();
        db.update("UPDATE purchase_order SET created_at=? WHERE id=?",java.time.LocalDateTime.of(2000,1,1,0,0),order);
        db.update("UPDATE order_line SET received_quantity=4,returned_quantity=1 WHERE order_id=?",order);
        mvc.perform(get("/api/requests/"+request+"/orders").with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].lines[0].net_received").value(3))
            .andExpect(jsonPath("$[0].lines[0].remaining").value(5));
        mvc.perform(get("/api/orders/"+order).with(user("employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/work-queue").with(user("warehouse")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.receipt[0].id").value(order));
        mvc.perform(get("/api/browse/products").param("q",sku).with(user("employee")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].replenishment[0].status").value("INBOUND"))
            .andExpect(jsonPath("$.items[0].replenishment[0].quantity").value(5));
        String question=json.writeValueAsString(Map.of("productId",product,"question","还要补多少？"));
        mvc.perform(post("/api/inventory/assistant").with(csrf()).contentType("application/json").content(question))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inventory/assistant").with(user("employee")).with(csrf()).contentType("application/json").content(question))
            .andExpect(status().isOk()).andExpect(jsonPath("$.facts.inbound").value(5))
            .andExpect(jsonPath("$.facts.suggested").value(5));
    }

    @Test void supplierCountsIncludeEveryProductRegardlessOfBrowsePageOrEnabledState() throws Exception {
        String name="supplier-"+UUID.randomUUID();
        db.update("INSERT INTO supplier(name,contact,phone) VALUES (?,'联系人','123')",name);
        long id=db.queryForObject("SELECT id FROM supplier WHERE name=?",Long.class,name);
        for(int i=0;i<13;i++)db.update("INSERT INTO product(sku,name,category,unit,price,min_stock,supplier_id,enabled) VALUES (?,?,'测试','件',1,0,?,?)","T-"+UUID.randomUUID(),"商品"+i,id,i%2==0);
        mvc.perform(get("/api/browse/products?page=2&size=10").with(user("admin"))).andExpect(status().isOk());
        var response=mvc.perform(get("/api/suppliers").with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var supplier=json.readTree(response).findValues("id");
        assertFalse(supplier.isEmpty());
        for(var row:json.readTree(response))if(row.get("id").asLong()==id){assertEquals(13,row.get("product_count").asInt());return;}
        fail("Missing supplier");
    }

    @Test void notificationPageClampsAfterAllUnreadItemsDisappear() {
        db.update("DELETE FROM app_notification WHERE user_id=1");
        for(int i=0;i<21;i++)notifications.user(1,"通知",String.valueOf(i),"inventory");
        var second=notifications.inbox(admin,2,20,true);
        assertEquals(2,second.get("page"));
        assertEquals(1,((List<?>)second.get("items")).size());
        notifications.readAll(admin);
        var empty=notifications.inbox(admin,2,20,true);
        assertEquals(1,empty.get("page"));
        assertEquals(0L,empty.get("total"));
        assertEquals(0L,empty.get("unread"));
        assertTrue(((List<?>)empty.get("items")).isEmpty());
        assertEquals(21L,notifications.inbox(admin,1,20,false).get("total"));
    }

    @Test void notificationClampingPreservesRecipientScopeAndLastAvailableRows() {
        db.update("DELETE FROM app_notification WHERE user_id=1");
        notifications.user(1,"自己的通知","可见","inventory");
        notifications.user(2,"别人的通知","不可见","inventory");
        var inbox=notifications.inbox(admin,Integer.MAX_VALUE,20,false);
        assertEquals(1,inbox.get("page"));
        assertEquals(1L,inbox.get("total"));
        var items=(List<?>)inbox.get("items");
        assertEquals(1,items.size());
        assertEquals("自己的通知",((Map<?,?>)items.get(0)).get("title"));
    }
}
