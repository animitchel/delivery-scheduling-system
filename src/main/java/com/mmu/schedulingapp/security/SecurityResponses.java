package com.mmu.schedulingapp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class SecurityResponses {
    private SecurityResponses() { }

    static void json(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body);
    }

    static void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) {
            json(response, 401, "{\"error\":\"Authentication required.\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/html/login.html");
        }
    }
}
