package com.mmu.schedulingapp.security;

import com.mmu.schedulingapp.account.AccountAuthenticationProvider;
import com.mmu.schedulingapp.account.AccountRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http, AccountAuthenticationProvider provider,
                                            AccountRepository accounts) throws Exception {
        http.authenticationProvider(provider)
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/html/login.html", "/js/login.js", "/js/auth.js",
                                "/css/login.css", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/html/change-password.html", "/js/password.js").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
                        .requestMatchers("/api/admin/accounts/**").hasRole("ADMIN")
                        .requestMatchers("/api/driver/**").hasRole("DRIVER")
                        .requestMatchers(HttpMethod.GET, "/html/operators.html", "/js/operators.js").hasAnyRole("ADMIN", "DRIVER")
                        .requestMatchers("/api/v1/scheduling-app/**", "/html/**", "/js/admin.js",
                                "/js/operators.js", "/js/accounts.js", "/css/accounts.css").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/html/login.html").loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                SecurityResponses.json(response, 200, "{\"authenticated\":true}"))
                        .failureHandler((request, response, exception) ->
                                SecurityResponses.json(response, 401, "{\"error\":\"Invalid username or password.\"}")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                        .clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityResponses.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityResponses.json(response, 403, "{\"error\":\"Access denied.\"}")))
                .addFilterBefore(new AccountSessionFilter(accounts), AuthorizationFilter.class);
        // Session CSRF tokens and Spring's XOR request handler remain enabled, including login/logout.
        return http.build();
    }
}
