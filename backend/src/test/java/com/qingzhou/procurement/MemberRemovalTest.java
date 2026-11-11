package com.qingzhou.procurement;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","app.demo=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class MemberRemovalTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords; @Autowired ProcurementService procurement; @Autowired ApprovalRouting routing;
    String key(){return UUID.randomUUID().toString();}
    long member(String role){String name="remove_"+key().substring(0,10);db.update("INSERT INTO app_user(username,password_hash,display_name,department,role) VALUES (?,?,'删除测试成员','产品研发部',?)",name,passwords.encode("Original123!"),role);long id=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,name);db.update("INSERT INTO mutation_lock(actor_id) VALUES (?)",id);return id;}
    String username(long id){return db.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,id);}
    void remove(long id,String actor,int version,String confirmation,String key,int expected) throws Exception {
        mvc.perform(post("/api/users/"+id+"/remove").with(user(actor)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(Map.of("version",version,"username",confirmation,"reason","测试账号清理")))).andExpect(status().is(expected));
    }
    Account actor(long id,String role){return new Account(id,username(id),"测试成员","产品研发部",role);}

    @Test void removalHidesMemberRevokesLoginAndPreservesHistoricalReferences() throws Exception {
        long id=member("EMPLOYEE");String name=username(id);
        var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","Original123!")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        var request=procurement.createRequest(actor(id,"EMPLOYEE"),new ApiController.RequestInput(1L,1,"历史采购申请",null,1L),key());String requestId=request.get("id").toString();
        db.update("UPDATE purchase_request SET status='APPROVED',reviewer_id=1 WHERE id=?",requestId);
        remove(id,"admin",0,name,key(),200);
        assertTrue(db.queryForObject("SELECT deleted FROM app_user WHERE id=?",Boolean.class,id));
        assertFalse(db.queryForObject("SELECT enabled FROM app_user WHERE id=?",Boolean.class,id));
        assertEquals(id,db.queryForObject("SELECT applicant_id FROM purchase_request WHERE id=?",Long.class,requestId));
        var rows=json.readTree(mvc.perform(get("/api/users").with(user("admin"))).andReturn().getResponse().getContentAsString());
        for(var row:rows)assertNotEquals(id,row.get("id").asLong());
        mvc.perform(get("/api/products").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","Original123!")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/"+id).with(user("admin")).with(csrf()).contentType("application/json").content("{\"name\":\"重新启用\",\"department\":\"产品研发部\",\"role\":\"EMPLOYEE\",\"enabled\":true,\"version\":1}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/users/"+id+"/reset-password").with(user("admin")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"password\":\"Temporary123!\",\"version\":1}")).andExpect(status().isNotFound());
    }
    @Test void removalRequiresAdministratorCsrfAndCannotTargetSelf() throws Exception {
        long id=member("EMPLOYEE");
        remove(id,"employee",0,username(id),key(),403);
        remove(1,"admin",0,"admin",key(),409);
        mvc.perform(post("/api/users/"+id+"/remove").with(user("admin")).header("Idempotency-Key",key()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertFalse(db.queryForObject("SELECT deleted FROM app_user WHERE id=?",Boolean.class,id));
    }
    @Test void pendingApplicantsAndAssignedApproversMustResolveTasksBeforeRemoval() throws Exception {
        long applicant=member("EMPLOYEE"),approver=member("MANAGER");
        var request=procurement.createRequest(actor(applicant,"EMPLOYEE"),new ApiController.RequestInput(1L,1,"未完成审批",null,approver),key());
        remove(applicant,"admin",0,username(applicant),key(),409);
        remove(approver,"admin",0,username(approver),key(),409);
        assertFalse(db.queryForObject("SELECT deleted FROM app_user WHERE id=?",Boolean.class,approver));
        db.update("UPDATE purchase_request SET lifecycle_state='WITHDRAWN' WHERE id=?",request.get("id"));
        remove(approver,"admin",0,username(approver),key(),200);
        db.update("UPDATE app_user SET enabled=TRUE WHERE id=?",approver);
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",username(approver)).param("password","Original123!")).andExpect(status().isUnauthorized());
        assertFalse(routing.candidates(actor(applicant,"EMPLOYEE")).stream().anyMatch(row->((Number)row.get("id")).longValue()==approver));
        assertThrows(BusinessError.class,()->routing.validate(actor(applicant,"EMPLOYEE"),approver));
    }
    @Test void versionAndTypedConfirmationAreCheckedAndRetriesAreIdempotent() throws Exception {
        long id=member("EMPLOYEE");String name=username(id),k=key();
        remove(id,"admin",9,name,key(),409);
        remove(id,"admin",0,"错误账号",key(),400);
        remove(id,"admin",0,name,k,200);
        remove(id,"admin",0,name,k,200);
        remove(id,"admin",1,name,k,409);
        assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='删除团队成员' AND target=?",Long.class,name));
        assertEquals(1,db.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Integer.class,id));
    }
    @Test void deletingAnotherAdministratorPreservesAnActiveAdministrator() throws Exception {
        long id=member("ADMIN");
        remove(id,"admin",0,username(id),key(),200);
        assertTrue(db.queryForObject("SELECT enabled FROM app_user WHERE id=1",Boolean.class));
        assertThrows(BusinessError.class,()->routing.validate(actor(id,"ADMIN"),1L));
    }
}
