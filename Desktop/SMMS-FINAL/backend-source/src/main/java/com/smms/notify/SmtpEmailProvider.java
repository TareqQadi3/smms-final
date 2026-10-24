package com.smms.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Email through the application's {@link JavaMailSender} (spring-boot-starter-mail, already a
 * dependency; host and credentials come from {@code spring.mail.*}). Used only when
 * {@code app.notifications.email.enabled=true} AND the configuration validates; see
 * {@link #validate}. Not verified against a real mail server in this program (no credentials).
 */
public final class SmtpEmailProvider implements NotificationProvider {

    public static final String NAME = "smtp";
    private static final Pattern SIMPLE_ADDRESS = Pattern.compile("^[^@\\s<>,;]{1,64}@[^@\\s<>,;]{1,190}\\.[^@\\s<>,;]{2,}$");

    private final JavaMailSender sender;
    private final String from;
    private final String subjectPrefix;

    public SmtpEmailProvider(JavaMailSender sender, String from, String subjectPrefix) {
        this.sender = sender;
        this.from = from;
        this.subjectPrefix = subjectPrefix == null ? "" : subjectPrefix.replaceAll("[\\r\\n]+", " ");
    }

    /** Problems that make the provider unusable; empty means usable. Never contains a secret. */
    public static List<String> validate(NotificationProperties.Email cfg, JavaMailSender sender) {
        List<String> problems = new ArrayList<>();
        if (sender == null) {
            problems.add("no JavaMailSender bean: set spring.mail.host");
        } else if (sender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl
                   && (impl.getHost() == null || impl.getHost().isBlank())) {
            problems.add("spring.mail.host is empty");
        }
        if (cfg.getFrom() == null || cfg.getFrom().isBlank()) {
            problems.add("app.notifications.email.from is required");
        } else if (!isValidAddress(cfg.getFrom().trim())) {
            problems.add("app.notifications.email.from is not a valid address");
        }
        return problems;
    }

    public static boolean isValidAddress(String address) {
        if (address == null || address.length() > 254 || !SIMPLE_ADDRESS.matcher(address).matches()) return false;
        try {
            new InternetAddress(address, true).validate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public DeliveryChannel channel() { return DeliveryChannel.email; }
    @Override public String name() { return NAME; }
    @Override public boolean available() { return true; }

    @Override public ProviderStatus status() {
        return new ProviderStatus(DeliveryChannel.email, NAME, true, true, List.of());
    }

    @Override public SendResult send(OutboundMessage m) {
        if (!isValidAddress(m.destination())) return SendResult.permanentFailure("invalid destination address");
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(m.destination());
            helper.setSubject(truncate(subjectPrefix + stripLineBreaks(m.subject()), 200));
            helper.setText(m.body() == null ? "" : m.body(), false);
            sender.send(mime);
            String id = null;
            try { id = mime.getMessageID(); } catch (Exception ignored) { /* id is optional */ }
            return SendResult.ok(id == null ? null : truncate(id, 120));
        } catch (MailAuthenticationException | MailParseException e) {
            return SendResult.permanentFailure(e.getClass().getSimpleName());
        } catch (Exception e) {
            // MailSendException (connection refused, timeout, 4xx/5xx): retry with backoff
            return SendResult.transientFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String stripLineBreaks(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
