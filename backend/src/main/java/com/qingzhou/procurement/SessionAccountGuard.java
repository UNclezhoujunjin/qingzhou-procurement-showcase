package com.qingzhou.procurement;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Recheck persisted account state for every API, including read-only endpoints. */
@Configuration
class SessionAccountGuard implements WebMvcConfigurer {
    private final Accounts accounts;
    SessionAccountGuard(Accounts accounts) { this.accounts=accounts; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                try {
                    var a=accounts.current(SecurityContextHolder.getContext().getAuthentication());
                    boolean allowed=request.getRequestURI().equals("/api/auth/me")||request.getRequestURI().equals("/api/profile/password")||request.getRequestURI().equals("/api/profile")&&request.getMethod().equals("GET");
                    if(a.mustChangePassword()&&!allowed)throw new BusinessError(403,"请先在个人中心修改管理员重置的密码");
                }
                catch(BusinessError e) {
                    if(e.status==401) {
                        var session=request.getSession(false);
                        if(session!=null) session.invalidate();
                        SecurityContextHolder.clearContext();
                    }
                    throw e;
                }
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/csrf","/api/auth/config","/api/auth/login","/api/auth/logout");
    }
}
