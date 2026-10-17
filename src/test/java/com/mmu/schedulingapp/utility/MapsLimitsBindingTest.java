package com.mmu.schedulingapp.utility;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class MapsLimitsBindingTest {
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(MapsLimits.class) static class Config { }
    @org.junit.jupiter.api.Test void documentedEnvironmentNamesBind() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", java.util.Map.of(
                                "SCHEDULING_MAPS_CONNECTTIMEOUTMS", "321",
                                "SCHEDULING_MAPS_PLANNINGTIMEOUTMS", "4321",
                                "SCHEDULING_MAPS_MAXDELIVERIES", "12",
                                "SCHEDULING_MAPS_MAXOPERATORCANDIDATES", "123"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var limits=context.getBean(MapsLimits.class);
                    assertThat(limits.getConnectTimeoutMs()).isEqualTo(321);
                    assertThat(limits.getPlanningTimeoutMs()).isEqualTo(4321);
                    assertThat(limits.getMaxDeliveries()).isEqualTo(12);
                    assertThat(limits.getMaxOperatorCandidates()).isEqualTo(123);
                });
    }
    @ParameterizedTest @ValueSource(strings={"connect-timeout-ms=0","read-timeout-ms=0","request-timeout-ms=0","max-response-bytes=0","max-deliveries=0","max-operator-candidates=0","max-operator-candidates=501","max-provider-calls=0","max-concurrent-planning=0","planning-timeout-ms=0"})
    void unsafeLimitsFailStartup(String setting) {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("scheduling.maps."+setting)
                .run(context -> assertThat(context).hasFailed());
    }
}
