package com.paytm.reservation.security;

import com.paytm.reservation.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Check if route requires authenticated user
        boolean isReserveEndpoint = path.matches("^/shows/[^/]+/reserve/?$") && "POST".equalsIgnoreCase(method);
        boolean isCancelEndpoint = path.matches("^/reservations/[^/]+/cancel/?$") && "POST".equalsIgnoreCase(method);

        String authHeader = request.getHeader("Authorization");
        String userId = null;

        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                userId = token;
            }
        }

        if (isReserveEndpoint || isCancelEndpoint) {
            if (userId == null) {
                throw new UnauthorizedException("Authentication required. Please provide a valid Bearer token in Authorization header");
            }
            UserContext.setUserId(userId);
            MDC.put("user_id", userId);
        } else if (userId != null) {
            UserContext.setUserId(userId);
            MDC.put("user_id", userId);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
