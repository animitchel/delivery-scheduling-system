package com.mmu.schedulingapp.utility;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Fixed production URLs are constructed by GoogleMapsClient, never supplied by API callers. */
@Component
public class MapsHttpTransport implements AutoCloseable {
    private final MapsLimits limits;
    private final HttpClient client;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maps-read-deadline"); thread.setDaemon(true); return thread;
    });

    public MapsHttpTransport(MapsLimits limits) {
        this.limits = limits;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(limits.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String get(URI uri, long remainingNanos) throws IOException {
        long timeout = Math.min(TimeUnit.MILLISECONDS.toNanos(limits.getRequestTimeoutMs()), remainingNanos);
        if (timeout <= 0) throw new MapsFailure(MapsFailure.Kind.TIMEOUT);
        long started = System.nanoTime();
        // Request timeout also bounds waiting for headers; body idle and total deadlines are separate.
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(timeout,
                TimeUnit.MILLISECONDS.toNanos(limits.getReadTimeoutMs())))).GET().build();
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, info -> {
            if (info.statusCode() != 200) {
                MapsFailure.Kind kind = info.statusCode() == 429 ? MapsFailure.Kind.QUOTA
                        : info.statusCode() == 401 || info.statusCode() == 403 ? MapsFailure.Kind.DENIED : MapsFailure.Kind.HTTP_FAILURE;
                return new LimitedBody(new MapsFailure(kind));
            }
            long length = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            return new LimitedBody(length > limits.getMaxResponseBytes() ? new MapsFailure(MapsFailure.Kind.RESPONSE_TOO_LARGE) : null);
        });
        try {
            long remaining = timeout - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            return pending.get(remaining, TimeUnit.NANOSECONDS).body();
        } catch (TimeoutException e) {
            pending.cancel(true); throw new MapsFailure(MapsFailure.Kind.TIMEOUT);
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new MapsFailure(MapsFailure.Kind.UNAVAILABLE);
        } catch (ExecutionException e) {
            pending.cancel(true);
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof MapsFailure failure) throw failure;
            if (cause instanceof HttpTimeoutException) throw new MapsFailure(MapsFailure.Kind.TIMEOUT);
            throw new MapsFailure(MapsFailure.Kind.UNAVAILABLE);
        }
    }

    private final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final MapsFailure rejection;
        private Flow.Subscription subscription;
        private ScheduledFuture<?> idle;
        LimitedBody(MapsFailure rejection) { this.rejection = rejection; }
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejection != null) { fail(rejection); return; }
            arm(); subscription.request(1);
        }
        private void arm() {
            if (idle != null) idle.cancel(false);
            idle = timers.schedule(() -> fail(new MapsFailure(MapsFailure.Kind.TIMEOUT)), limits.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        private synchronized void fail(MapsFailure failure) {
            if (result.isDone()) return;
            if (idle != null) idle.cancel(false);
            // Publish the classified failure before cancel, which may synchronously call onError.
            result.completeExceptionally(failure);
            if (subscription != null) subscription.cancel();
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limits.getMaxResponseBytes() - bytes.size()) {
                    fail(new MapsFailure(MapsFailure.Kind.RESPONSE_TOO_LARGE)); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            arm(); subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { fail(new MapsFailure(MapsFailure.Kind.UNAVAILABLE)); }
        @Override public synchronized void onComplete() {
            if (idle != null) idle.cancel(false);
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
    @Override @PreDestroy public void close() { client.shutdownNow(); timers.shutdownNow(); }
}
