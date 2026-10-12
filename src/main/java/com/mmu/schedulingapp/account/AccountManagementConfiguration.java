package com.mmu.schedulingapp.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class AccountManagementConfiguration {
    @Bean Clock accountClock() { return Clock.systemUTC(); }
}
