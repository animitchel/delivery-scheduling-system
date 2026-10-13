package com.mmu.schedulingapp.utility;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class PlanningGuardTest {
    @Test void monotonicDeadlineExpiresAndIsNeverResetByCalls() {
        var limits=new MapsLimits(); limits.setPlanningTimeoutMs(10);
        var now=new AtomicLong(); var releases=new AtomicInteger();
        try(var budget=new PlanningGuard.Budget(limits,now::get,releases::incrementAndGet)) {
            budget.countProviderCall(); now.set(9_000_000); budget.countProviderCall(); now.set(10_000_000);
            assertThatThrownBy(budget::check).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(504));
        }
        assertThat(releases).hasValue(1);
    }
    @Test void callBudgetCannotBeBypassedByFailedRequests() {
        var limits=new MapsLimits(); limits.setMaxProviderCalls(1);
        try(var budget=new PlanningGuard.Budget(limits,() -> 0,() -> {})) {
            budget.countProviderCall();
            assertThatThrownBy(budget::countProviderCall).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(422));
        }
    }
    @Test void excessWorkIsRejectedAndSlotReleasedOnFailure() {
        var limits=new MapsLimits(); limits.setMaxConcurrentPlanning(1); var guard=new PlanningGuard(limits);
        try(var budget=guard.begin()) {
            assertThatThrownBy(guard::begin).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
            budget.close(); budget.close();
            try(var next=guard.begin()) { next.check(); }
        }
        try(var next=guard.begin()) { next.check(); }
    }
}
