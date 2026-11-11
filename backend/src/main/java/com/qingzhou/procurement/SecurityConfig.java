package com.qingzhou.procurement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(JdbcTemplate db) {
        return username -> db.query("SELECT * FROM app_user WHERE username=? AND deleted=FALSE", (rs, n) ->
            new StoredUser(rs.getString("username"),rs.getString("password_hash"),rs.getString("role"),
                rs.getBoolean("enabled"),rs.getInt("auth_epoch")), username)
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/config", "/api/auth/login", "/actuator/health", "/error").permitAll()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> { res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"登录成功\"}"); })
                .failureHandler((req, res, error) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"账号或密码不正确\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, error) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"登录已失效，请重新登录\"}"); })
                .accessDeniedHandler((req, res, error) -> { res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"请求被拒绝，请刷新页面后重试\"}"); }));
        return http.build();
    }
}

class StoredUser extends User {
    final int version;
    final String passwordMarker;
    StoredUser(String username,String password,String role,boolean enabled,int version) {
        super(username,password,enabled,true,true,true,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role)));
        this.version=version;
        this.passwordMarker=marker(password);
    }
    static String marker(String hash){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
