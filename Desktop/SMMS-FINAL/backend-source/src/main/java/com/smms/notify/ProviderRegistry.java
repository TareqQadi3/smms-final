package com.smms.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the provider of each channel once at startup from {@link NotificationProperties}.
 * Fail-safe: a channel that is switched off, or switched on with an invalid configuration, gets the
 * {@link NotConfiguredProvider}; the problem is logged (never a secret) and the application starts
 * normally. In-app notifications do not depend on any of this.
 */
@Component
@Slf4j
public class ProviderRegistry {

    private final Map<DeliveryChannel, NotificationProvider> providers = new EnumMap<>(DeliveryChannel.class);

    public ProviderRegistry(NotificationProperties props, ObjectProvider<JavaMailSender> mailSender) {
        providers.put(DeliveryChannel.email, buildEmail(props, safeMailSender(mailSender)));
        providers.put(DeliveryChannel.sms, buildSms(props));
        for (NotificationProvider p : providers.values()) {
            ProviderStatus s = p.status();
            if (p.available()) {
                log.info("Notification provider ready: channel={} provider={}", s.channel(), s.provider());
            } else if (s.enabled()) {
                log.warn("Notification channel {} is enabled but unusable, it stays DISABLED: {}", s.channel(), s.problems());
            } else {
                log.info("Notification channel {} is off (no external delivery, in-app unaffected)", s.channel());
            }
        }
    }

    /** For tests: explicit providers. */
    public ProviderRegistry(NotificationProvider email, NotificationProvider sms) {
        providers.put(DeliveryChannel.email, email);
        providers.put(DeliveryChannel.sms, sms);
    }

    public NotificationProvider forChannel(DeliveryChannel channel) {
        NotificationProvider p = providers.get(channel);
        return p != null ? p : new NotConfiguredProvider(channel);
    }

    public List<ProviderStatus> statuses() {
        return List.of(forChannel(DeliveryChannel.email).status(), forChannel(DeliveryChannel.sms).status());
    }

    private static JavaMailSender safeMailSender(ObjectProvider<JavaMailSender> p) {
        try { return p.getIfAvailable(); } catch (RuntimeException e) { return null; }
    }

    static NotificationProvider buildEmail(NotificationProperties props, JavaMailSender sender) {
        NotificationProperties.Email cfg = props.getEmail();
        if (!cfg.isEnabled()) return new NotConfiguredProvider(DeliveryChannel.email);
        try {
            List<String> problems = SmtpEmailProvider.validate(cfg, sender);
            if (!problems.isEmpty()) return new NotConfiguredProvider(DeliveryChannel.email, true, problems);
            return new SmtpEmailProvider(sender, cfg.getFrom().trim(), cfg.getSubjectPrefix());
        } catch (RuntimeException e) {
            return new NotConfiguredProvider(DeliveryChannel.email, true, List.of("provider setup failed: " + e.getClass().getSimpleName()));
        }
    }

    static NotificationProvider buildSms(NotificationProperties props) {
        NotificationProperties.Sms cfg = props.getSms();
        if (!cfg.isEnabled()) return new NotConfiguredProvider(DeliveryChannel.sms);
        try {
            List<String> problems = HttpSmsProvider.validate(cfg);
            if (!problems.isEmpty()) return new NotConfiguredProvider(DeliveryChannel.sms, true, problems);
            return new HttpSmsProvider(cfg.getUrl(), cfg.getToken(), cfg.getSenderId());
        } catch (RuntimeException e) {
            return new NotConfiguredProvider(DeliveryChannel.sms, true, List.of("provider setup failed: " + e.getClass().getSimpleName()));
        }
    }
}
