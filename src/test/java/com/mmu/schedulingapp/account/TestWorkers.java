package com.mmu.schedulingapp.account;

import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.*;

/** Blocking race tests need independent workers even on a two-core CI runner. */
final class TestWorkers implements BeforeEachCallback, AfterEachCallback {
    private ExecutorService executor;
    @Override public void beforeEach(ExtensionContext context) {
        executor = Executors.newFixedThreadPool(4);
    }
    <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }
    CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }
    @Override public void afterEach(ExtensionContext context) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test workers did not stop; database cleanup cannot safely continue.");
            }
            throw new AssertionError("Test workers exceeded their cleanup deadline.");
        }
    }
}
