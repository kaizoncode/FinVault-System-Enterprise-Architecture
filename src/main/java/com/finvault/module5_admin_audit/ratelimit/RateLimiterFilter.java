package com.finvault.module5_admin_audit.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ==============================================================================
 * [Owner: Member 5 - Admin Live CMS & Audit Logging Module]
 * RateLimiterFilter: Bộ lọc HTTP chặn các yêu cầu vượt ngưỡng tần suất (HTTP 429 Too Many Requests)
 * ==============================================================================
 */
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimiterFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Chỉ áp dụng giới hạn tốc độ trên các API endpoint
        return !path.startsWith("/api/") || path.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientKey = resolveClientKey(request);

        if (!rateLimiterService.tryConsume(clientKey)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Quá nhiều yêu cầu. Vui lòng thử lại sau giây lát (Rate limit exceeded)\",\"errorCode\":\"TOO_MANY_REQUESTS\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "USER:" + auth.getName();
        }

        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return "IP:" + xf.split(",")[0].trim();
        }
        return "IP:" + request.getRemoteAddr();
    }
}
