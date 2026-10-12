package com.mmu.schedulingapp.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;

@Configuration
public class AccountPasswordConfiguration {
    @Bean
    PasswordEncoder accountPasswordEncoder() {
        // Recognize only bcrypt hashes; never accept a plaintext/noop fallback.
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }
}
