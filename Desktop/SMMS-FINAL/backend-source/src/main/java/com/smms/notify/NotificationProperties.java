package com.smms.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Settings of the external notification channels ({@code app.notifications.*}). Every value has a
 * safe default: with no configuration nothing is sent, in-app notifications keep working and the
 * dispatcher records {@code not_configured}. Secrets (SMS token, SMTP password) come from the
 * environment only and are never logged or returned by an endpoint.
 * <p>
 * Keys: {@code scheduler-enabled}, {@code dispatch-ms}, {@code enqueue-ms}, {@code batch-size},
 * {@code max-attempts}, {@code lease-seconds}, {@code lookback-hours}, {@code email.enabled},
 * {@code email.from}, {@code email.subject-prefix}, {@code sms.enabled}, {@code sms.url},
 * {@code sms.token}, {@code sms.sender-id}.
 */
@Component
@ConfigurationProperties(prefix = "app.notifications")
public class NotificationProperties {

    /** Retry delays after failed attempt 1, 2, 3, ...; a failure beyond this list is the last attempt. */
    public static final List<Duration> BACKOFF = List.of(
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
        Duration.ofHours(2), Duration.ofHours(6));

    /** Turns the two background jobs off (tests, or a node that must not deliver). */
    private boolean schedulerEnabled = true;
    private long dispatchMs = 30_000;
    private long enqueueMs = 30_000;
    private int batchSize = 50;
    /** Total attempts including the first; the attempt that fails at this count makes the row dead. */
    private int maxAttempts = BACKOFF.size() + 1;
    /** A claimed row is not picked up again before this lease expires (crash safety). */
    private int leaseSeconds = 300;
    /** Notifications older than this are never turned into deliveries (no blast after an outage). */
    private int lookbackHours = 24;
    private final Email email = new Email();
    private final Sms sms = new Sms();

    public static class Email {
        private boolean enabled = false;
        private String from;
        private String subjectPrefix = "[SMMS] ";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }
    }

    public static class Sms {
        private boolean enabled = false;
        private String url;
        private String token;
        private String senderId;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getSenderId() { return senderId; }
        public void setSenderId(String senderId) { this.senderId = senderId; }
    }

    /** Delay before the next attempt after {@code attemptsMade} failed attempts, or null when exhausted. */
    public Duration backoffAfter(int attemptsMade) {
        if (attemptsMade < 1 || attemptsMade >= effectiveMaxAttempts()) return null;
        return BACKOFF.get(Math.min(attemptsMade, BACKOFF.size()) - 1);
    }

    public int effectiveMaxAttempts() {
        return Math.max(1, Math.min(maxAttempts, 20));
    }

    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean v) { this.schedulerEnabled = v; }
    public long getDispatchMs() { return dispatchMs; }
    public void setDispatchMs(long v) { this.dispatchMs = v; }
    public long getEnqueueMs() { return enqueueMs; }
    public void setEnqueueMs(long v) { this.enqueueMs = v; }
    public int getBatchSize() { return Math.max(1, Math.min(batchSize, 500)); }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public int getLeaseSeconds() { return Math.max(30, leaseSeconds); }
    public void setLeaseSeconds(int v) { this.leaseSeconds = v; }
    public int getLookbackHours() { return Math.max(1, Math.min(lookbackHours, 24 * 30)); }
    public void setLookbackHours(int v) { this.lookbackHours = v; }
    public Email getEmail() { return email; }
    public Sms getSms() { return sms; }
}
