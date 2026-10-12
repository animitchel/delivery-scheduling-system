package com.mmu.schedulingapp.account;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ProvisionAdminRunnerTest {
    @Test void inactiveWithoutExplicitProfile() {
        new ApplicationContextRunner().withUserConfiguration(ProvisionAdminRunner.class).run(context -> assertThat(context).doesNotHaveBean(ProvisionAdminRunner.class));
    }
    @Test void inactiveWithProvisionProfileButWithoutLocal() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("provision-admin"))
                .withUserConfiguration(ProvisionAdminRunner.class)
                .run(context -> assertThat(context).doesNotHaveBean(ProvisionAdminRunner.class));
    }
    @Test void inactiveWithLocalButWithoutProvisionProfile() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withUserConfiguration(ProvisionAdminRunner.class)
                .run(context -> assertThat(context).doesNotHaveBean(ProvisionAdminRunner.class));
    }
    @Test void enabledOnlyWithBothProfiles() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "provision-admin"))
                .withBean(AccountService.class, () -> mock(AccountService.class))
                .withUserConfiguration(ProvisionAdminRunner.class)
                .run(context -> assertThat(context).hasSingleBean(ProvisionAdminRunner.class));
    }
    @Test void refusesWebModeBeforeAnyAccountOperation() {
        var service = mock(AccountService.class);
        var runner = new ProvisionAdminRunner(service, new MockEnvironment(), new GenericWebApplicationContext());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class).hasMessageContaining("web-application-type=none");
        verifyNoInteractions(service);
    }
    @Test void passesOnlyExplicitCredentialsAndLogsNoSecrets(CapturedOutput output) {
        var service = mock(AccountService.class);
        when(service.provisionInitialAdministrator("private.admin", "private long password")).thenReturn(Optional.of(new AccountSummary(1L,"private.admin",AccountRole.ADMIN,null,true)));
        new ProvisionAdminRunner(service, new MockEnvironment().withProperty("ACCOUNT_BOOTSTRAP_USERNAME","private.admin").withProperty("ACCOUNT_BOOTSTRAP_PASSWORD","private long password"), new GenericApplicationContext()).run(new DefaultApplicationArguments());
        verify(service).provisionInitialAdministrator("private.admin", "private long password");
        assertThat(output.getAll()).contains("Initial administrator provisioned.").doesNotContain("private.admin", "private long password");
    }
    @Test void sanitizesFailureWithoutLeakingCause(CapturedOutput output) {
        var service = mock(AccountService.class);
        when(service.provisionInitialAdministrator(null,null)).thenThrow(new IllegalArgumentException("private long password"));
        var runner = new ProvisionAdminRunner(service,new MockEnvironment(),new GenericApplicationContext());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("private long password");
        assertThat(output.getAll()).doesNotContain("private long password");
    }
    @Test void concurrentWinnerIsHandledWithoutResettingAccount() {
        var service = mock(AccountService.class);
        when(service.provisionInitialAdministrator(null,null)).thenThrow(new DataIntegrityViolationException("private hash"));
        when(service.hasAdministrator()).thenReturn(true);
        assertThatCode(() -> new ProvisionAdminRunner(service,new MockEnvironment(),new GenericApplicationContext()).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        verify(service).provisionInitialAdministrator(null,null);
        verify(service).hasAdministrator();
        verifyNoMoreInteractions(service);
    }
    @Test void constraintFailureWithoutAdministratorFailsClosed() {
        var service = mock(AccountService.class);
        when(service.provisionInitialAdministrator(null,null)).thenThrow(new DataIntegrityViolationException("private hash"));
        assertThatThrownBy(() -> new ProvisionAdminRunner(service,new MockEnvironment(),new GenericApplicationContext()).run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("private hash");
    }
}
