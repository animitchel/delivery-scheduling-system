package com.mmu.schedulingapp.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@Profile("local & provision-admin")
public class ProvisionAdminRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProvisionAdminRunner.class);
    private final AccountService accounts;
    private final Environment environment;
    private final ApplicationContext context;

    public ProvisionAdminRunner(AccountService accounts, Environment environment, ApplicationContext context) {
        this.accounts = accounts;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (context instanceof WebApplicationContext) {
            throw new IllegalStateException("Administrator provisioning requires spring.main.web-application-type=none.");
        }
        try {
            var created = accounts.provisionInitialAdministrator(
                    environment.getProperty("ACCOUNT_BOOTSTRAP_USERNAME"),
                    environment.getProperty("ACCOUNT_BOOTSTRAP_PASSWORD"));
            log.info(created.isPresent() ? "Initial administrator provisioned." : "Administrator already exists; no changes made.");
        } catch (DataIntegrityViolationException exception) {
            // A concurrent successful bootstrap wins the unique slot; never replace its credentials.
            if (!accounts.hasAdministrator()) {
                throw new IllegalStateException("Administrator provisioning failed; check database constraints.");
            }
            log.info("Administrator already exists; no changes made.");
        } catch (RuntimeException exception) {
            // Do not attach exception details that could include input or database values.
            throw new IllegalStateException("Administrator provisioning failed; check the supplied configuration.");
        }
    }
}
