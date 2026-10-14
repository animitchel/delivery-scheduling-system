package com.mmu.schedulingapp.frontend;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Explicit SPA entry points: APIs, missing assets and unknown paths never receive index HTML. */
@Controller
public class FrontendController {
    @GetMapping({"/app/login", "/app", "/app/", "/app/admin", "/app/admin/accounts", "/app/admin/recipients", "/app/admin/warehouses", "/app/admin/operators", "/app/admin/deliveries", "/app/admin/routes", "/app/admin/routes/generate", "/app/driver", "/app/driver/routes"})
    public String app(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return "forward:/app/index.html";
    }
    @GetMapping("/html/login.html")
    public org.springframework.http.ResponseEntity<Void> legacyLogin(
            @org.springframework.web.bind.annotation.RequestParam java.util.Map<String, String> parameters) {
        String destination = "/app/login";
        if (parameters.containsKey("passwordChanged")) destination += "?passwordChanged";
        else if (parameters.containsKey("loggedOut")) destination += "?loggedOut";
        return org.springframework.http.ResponseEntity.status(302)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, destination).build();
    }
}
