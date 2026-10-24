package com.smms.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generic SMS adapter: one JSON POST ({@code {"to","message","sender"}}) with a bearer token to a
 * URL from configuration. No vendor account exists in this program, so the contract is the
 * documented generic one; vendor-specific adapters are a later step. Safety: https is required
 * (plain http only for loopback), no credentials in the URL, 5 s timeouts, redirects are never
 * followed (a redirect would forward the bearer token), the token is never logged.
 */
public final class HttpSmsProvider implements NotificationProvider {

    public static final String NAME = "http-sms";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI uri;
    private final String token;
    private final String senderId;
    private final HttpClient client;

    public HttpSmsProvider(String url, String token, String senderId) {
        this.uri = URI.create(url.trim());
        this.token = token;
        this.senderId = senderId;
        this.client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public static List<String> validate(NotificationProperties.Sms cfg) {
        List<String> problems = new ArrayList<>();
        String url = cfg.getUrl();
        if (url == null || url.isBlank()) {
            problems.add("app.notifications.sms.url is required");
        } else {
            try {
                URI u = URI.create(url.trim());
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                String host = u.getHost();
                if (host == null) {
                    problems.add("app.notifications.sms.url has no host");
                } else if (u.getUserInfo() != null) {
                    problems.add("app.notifications.sms.url must not contain credentials");
                } else if (scheme.equals("https")) {
                    // ok
                } else if (scheme.equals("http") && isLoopback(host)) {
                    // plain http is accepted for local stubs only
                } else {
                    problems.add("app.notifications.sms.url must use https (http only for localhost)");
                }
            } catch (IllegalArgumentException e) {
                problems.add("app.notifications.sms.url is not a valid URL");
            }
        }
        if (cfg.getToken() == null || cfg.getToken().isBlank()) {
            problems.add("app.notifications.sms.token is required");
        }
        return problems;
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    @Override public DeliveryChannel channel() { return DeliveryChannel.sms; }
    @Override public String name() { return NAME; }
    @Override public boolean available() { return true; }

    @Override public ProviderStatus status() {
        return new ProviderStatus(DeliveryChannel.sms, NAME, true, true, List.of());
    }

    @Override public SendResult send(OutboundMessage m) {
        String to = m.destination() == null ? "" : m.destination().replaceAll("[\\s()-]", "");
        if (!PHONE.matcher(to).matches()) return SendResult.permanentFailure("invalid destination number");
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("to", to);
            payload.put("message", m.subject() == null ? "" : truncate(m.subject(), 300));
            if (senderId != null && !senderId.isBlank()) payload.put("sender", senderId);
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return SendResult.ok(extractId(resp.body()));
            String err = "HTTP " + code;
            // 4xx other than 408/429 will not get better by retrying (bad number, bad token)
            boolean retryable = code >= 500 || code == 408 || code == 429;
            return retryable ? SendResult.transientFailure(err) : SendResult.permanentFailure(err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.transientFailure("interrupted");
        } catch (Exception e) {
            return SendResult.transientFailure(e.getClass().getSimpleName());
        }
    }

    private static String extractId(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(body);
            for (String k : new String[] {"id", "messageId", "message_id"}) {
                if (n.hasNonNull(k) && n.get(k).isValueNode()) return truncate(n.get(k).asText(), 120);
            }
        } catch (Exception ignored) { /* body is optional */ }
        return null;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
