package com.mmu.schedulingapp.utility;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class PlanningGuard {
    private final MapsLimits limits;
    private final Semaphore slots;
    public PlanningGuard(MapsLimits limits) { this.limits = limits; slots = new Semaphore(limits.getMaxConcurrentPlanning()); }
    public Budget begin() {
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Route planning is busy. Try again later.");
        return new Budget(limits, System::nanoTime, slots::release);
    }
    public static final class Budget implements AutoCloseable {
        private final MapsLimits limits;
        private final LongSupplier clock;
        private final Runnable release;
        private final long start;
        private int calls;
        private boolean closed;
        public Budget(MapsLimits limits, LongSupplier clock, Runnable release) {
            this.limits = limits; this.clock = clock; this.release = release; start = clock.getAsLong();
        }
        public long remainingNanos() {
            long remaining = TimeUnit.MILLISECONDS.toNanos(limits.getPlanningTimeoutMs()) - (clock.getAsLong() - start);
            if (remaining <= 0) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Route planning deadline exceeded.");
            return remaining;
        }
        public void check() { remainingNanos(); }
        public void countProviderCall() {
            check();
            if (++calls > limits.getMaxProviderCalls()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Route planning provider-call budget exceeded.");
        }
        public int maxOperatorCandidates() { return limits.getMaxOperatorCandidates(); }
        public int maxDeliveries() { return limits.getMaxDeliveries(); }
        @Override public void close() { if (!closed) { closed = true; release.run(); } }
    }
}
