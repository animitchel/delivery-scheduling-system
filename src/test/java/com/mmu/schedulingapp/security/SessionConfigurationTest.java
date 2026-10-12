package com.mmu.schedulingapp.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionConfigurationTest {
    @Test void productionUsesSecureHttpOnlyLaxCookieAndThirtyMinuteCookieOnlySessions() {
        var factory = mock(ConfigurableServletWebServerFactory.class);
        new SessionConfiguration().sessionCookies(new MockEnvironment()).customize(factory);
        var captured = ArgumentCaptor.forClass(Session.class);
        verify(factory).setSession(captured.capture());
        var session = captured.getValue();
        assertThat(session.getTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(session.getTrackingModes()).containsExactly(Session.SessionTrackingMode.COOKIE);
        assertThat(session.getCookie().getSecure()).isTrue();
        assertThat(session.getCookie().getHttpOnly()).isTrue();
        assertThat(session.getCookie().getSameSite()).isEqualTo(Cookie.SameSite.LAX);
        assertThat(session.isPersistent()).isFalse();
    }
    @Test void onlyExplicitLocalProfileAllowsHttpSessionCookie() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("local");
        var factory = mock(ConfigurableServletWebServerFactory.class);
        new SessionConfiguration().sessionCookies(environment).customize(factory);
        var captured = ArgumentCaptor.forClass(Session.class);
        verify(factory).setSession(captured.capture());
        assertThat(captured.getValue().getCookie().getSecure()).isFalse();
        assertThat(captured.getValue().getCookie().getHttpOnly()).isTrue();
    }
}
