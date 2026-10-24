package com.smms.notify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSmsProviderTest {

    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger redirectedHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/send", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"id\":\"m-123\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> { redirectedHits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private HttpSmsProvider provider(String path) {
        return new HttpSmsProvider("http://localhost:" + server.getAddress().getPort() + path, "tok-1", "SMMS");
    }

    private OutboundMessage sms(String to) { return new OutboundMessage(DeliveryChannel.sms, to, "Ticket T-1 assigned", "x"); }

    @Test
    void postsJsonWithBearerAndReturnsProviderId() {
        SendResult r = provider("/send").send(sms("+966 50 123 4567"));
        assertThat(r.accepted()).isTrue();
        assertThat(r.providerMessageId()).isEqualTo("m-123");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(body.get()).contains("\"to\":\"+966501234567\"").contains("\"sender\":\"SMMS\"").contains("Ticket T-1 assigned");
    }

    @Test
    void serverErrorsAreRetryableClientErrorsPermanent() {
        status.set(503);
        assertThat(provider("/send").send(sms("+966501234567")).retryable()).isTrue();
        status.set(429);
        assertThat(provider("/send").send(sms("+966501234567")).retryable()).isTrue();
        status.set(400);
        SendResult r = provider("/send").send(sms("+966501234567"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.retryable()).isFalse();
        assertThat(r.error()).isEqualTo("HTTP 400");
    }

    @Test
    void redirectsAreNeverFollowed() {
        SendResult r = provider("/redirect").send(sms("+966501234567"));
        assertThat(r.accepted()).isFalse();
        assertThat(redirectedHits.get()).isZero();
    }

    @Test
    void badNumberIsPermanentWithoutNetworkCall() {
        SendResult r = provider("/send").send(sms("12"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.retryable()).isFalse();
        assertThat(body.get()).isNull();
    }

    @Test
    void unreachableHostIsRetryableNotAnException() {
        int port = server.getAddress().getPort();
        server.stop(0);
        SendResult r = new HttpSmsProvider("http://localhost:" + port + "/send", "t", null).send(sms("+966501234567"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.retryable()).isTrue();
    }
}
