package com.mmu.schedulingapp.utility;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MapsHttpTransportTest {
    HttpServer server;
    ExecutorService workers;
    MapsHttpTransport transport;
    MapsLimits limits;
    @BeforeEach void start() throws Exception {
        limits=new MapsLimits(); limits.setConnectTimeoutMs(100); limits.setReadTimeoutMs(150);
        limits.setRequestTimeoutMs(250); limits.setMaxResponseBytes(1024);
        transport=new MapsHttpTransport(limits);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.start();
    }
    @AfterEach void close() { transport.close(); server.stop(0); workers.shutdownNow(); }
    URI uri(String path) { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path+"?key=dummy-secret&address=private-address"); }
    void fail(String path,MapsFailure.Kind kind) {
        long start=System.nanoTime();
        assertThatThrownBy(() -> transport.get(uri(path),TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOf(MapsFailure.class).hasMessage("Google Maps request failed.").hasNoCause()
                .satisfies(e -> assertThat(((MapsFailure)e).kind()).isEqualTo(kind));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2000);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    @Test void successReadsUtf8AndRecoversAfterFailure() throws Exception {
        server.createContext("/ok", exchange -> {
            byte[] body="café".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()) { stream.write(body); }
        });
        server.createContext("/denied", exchange -> { exchange.sendResponseHeaders(403,-1); exchange.close(); });
        fail("/denied",MapsFailure.Kind.DENIED);
        assertThat(transport.get(uri("/ok"),TimeUnit.SECONDS.toNanos(1))).isEqualTo("café");
    }
    @Test void connectDeadlineBoundsStalledTlsHandshake() throws Exception {
        transport.close(); limits.setReadTimeoutMs(1500); limits.setRequestTimeoutMs(2000);
        transport=new MapsHttpTransport(limits);
        try(var socketServer=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            workers.submit(() -> { try(var socket=socketServer.accept()) { sleep(1200); } catch(java.io.IOException ignored) { } });
            long start=System.nanoTime();
            assertThatThrownBy(() -> transport.get(URI.create("https://127.0.0.1:"+socketServer.getLocalPort()+"/"),TimeUnit.SECONDS.toNanos(2)))
                    .isInstanceOf(MapsFailure.class).satisfies(e -> assertThat(((MapsFailure)e).kind()).isEqualTo(MapsFailure.Kind.TIMEOUT));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1000);
        }
    }
    @Test void stalledHeadersAreBounded() {
        server.createContext("/slow", exchange -> { sleep(500); try { exchange.sendResponseHeaders(200,-1); } finally { exchange.close(); } });
        fail("/slow",MapsFailure.Kind.TIMEOUT);
    }
    @Test void stalledBodyIsBounded() {
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200,0);
            try(var stream=exchange.getResponseBody()) { stream.write('a'); stream.flush(); sleep(500); stream.write('b'); }
        });
        fail("/slow",MapsFailure.Kind.TIMEOUT);
    }
    @Test void slowDripCannotExtendTotalRequestDeadline() {
        server.createContext("/drip", exchange -> {
            exchange.sendResponseHeaders(200,0);
            try(var stream=exchange.getResponseBody()) { for(int i=0;i<20;i++) { stream.write('a'); stream.flush(); sleep(40); } }
        });
        fail("/drip",MapsFailure.Kind.TIMEOUT);
    }
    @Test void planningRemainingTimeCapsRequest() {
        server.createContext("/slow", exchange -> { sleep(500); exchange.close(); });
        long start=System.nanoTime();
        assertThatThrownBy(() -> transport.get(uri("/slow"),TimeUnit.MILLISECONDS.toNanos(40))).isInstanceOf(MapsFailure.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1000);
    }
    @Test void declaredOversizeBodyIsRejected() {
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200,2048); try(var stream=exchange.getResponseBody()) { stream.write(new byte[2048]); }
        });
        fail("/big",MapsFailure.Kind.RESPONSE_TOO_LARGE);
    }
    @Test void chunkedOversizeBodyIsRejected() {
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200,0); try(var stream=exchange.getResponseBody()) { stream.write(new byte[2048]); }
        });
        fail("/big",MapsFailure.Kind.RESPONSE_TOO_LARGE);
    }
    @Test void quotaIsClassifiedWithoutReadingErrorBody() {
        server.createContext("/quota", exchange -> {
            exchange.sendResponseHeaders(429,0); try(var stream=exchange.getResponseBody()) { stream.write("private-provider-body".getBytes(StandardCharsets.UTF_8)); }
        });
        fail("/quota",MapsFailure.Kind.QUOTA);
    }
    @Test void redirectsAreRejectedWithoutForwardingCredentials() {
        var followed=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location",uri("/target").toString()); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.createContext("/target", exchange -> { followed.set(true); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        fail("/redirect",MapsFailure.Kind.HTTP_FAILURE); assertThat(followed).isFalse();
    }
}
