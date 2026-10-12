package com.mmu.schedulingapp.security;

import org.springframework.boot.web.servlet.server.Session;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import java.time.Duration;
import java.util.Set;

@Configuration
public class SessionConfiguration {
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> sessionCookies(Environment environment) {
        return factory -> {
            var session = new Session();
            session.setTimeout(Duration.ofMinutes(30));
            session.setTrackingModes(Set.of(Session.SessionTrackingMode.COOKIE));
            var cookie = session.getCookie();
            cookie.setHttpOnly(true);
            cookie.setSameSite(Cookie.SameSite.LAX);
            cookie.setSecure(!environment.acceptsProfiles(Profiles.of("local")));
            cookie.setPath("/");
            factory.setSession(session);
        };
    }
}
