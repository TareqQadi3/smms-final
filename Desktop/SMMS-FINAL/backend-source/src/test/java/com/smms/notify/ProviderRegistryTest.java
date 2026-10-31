package com.smms.notify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRegistryTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> mail(JavaMailSender s) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    private static JavaMailSenderImpl sender(String host) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        return s;
    }

    @Test
    void defaultsDisableBothChannelsAndNeverFail() {
        ProviderRegistry r = new ProviderRegistry(new NotificationProperties(), mail(sender("localhost")));
        for (DeliveryChannel c : DeliveryChannel.values()) {
            NotificationProvider p = r.forChannel(c);
            assertThat(p.available()).isFalse();
            assertThat(p.name()).isEqualTo("none");
            assertThat(p.status().enabled()).isFalse();
            assertThat(p.send(new OutboundMessage(c, "a@b.co", "s", "b")).accepted()).isFalse();
        }
    }

    @Test
    void enabledEmailWithoutFromIsDisabledAndReportedNotFatal() {
        NotificationProperties props = new NotificationProperties();
        props.getEmail().setEnabled(true);
        ProviderRegistry r = new ProviderRegistry(props, mail(sender("localhost")));
        ProviderStatus s = r.forChannel(DeliveryChannel.email).status();
        assertThat(r.forChannel(DeliveryChannel.email).available()).isFalse();
        assertThat(s.enabled()).isTrue();
        assertThat(s.configured()).isFalse();
        assertThat(s.problems()).anyMatch(x -> x.contains("email.from"));
    }

    @Test
    void enabledEmailWithoutMailHostOrSenderIsDisabled() {
        NotificationProperties props = new NotificationProperties();
        props.getEmail().setEnabled(true);
        props.getEmail().setFrom("noreply@example.org");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.email).status().problems())
            .anyMatch(x -> x.contains("JavaMailSender"));
        assertThat(new ProviderRegistry(props, mail(sender(""))).forChannel(DeliveryChannel.email).status().problems())
            .anyMatch(x -> x.contains("spring.mail.host"));
    }

    @Test
    void validEmailConfigurationActivatesSmtp() {
        NotificationProperties props = new NotificationProperties();
        props.getEmail().setEnabled(true);
        props.getEmail().setFrom("noreply@example.org");
        NotificationProvider p = new ProviderRegistry(props, mail(sender("smtp.example.org"))).forChannel(DeliveryChannel.email);
        assertThat(p.available()).isTrue();
        assertThat(p.name()).isEqualTo("smtp");
    }

    @Test
    void smsUrlRules() {
        NotificationProperties props = new NotificationProperties();
        props.getSms().setEnabled(true);
        props.getSms().setToken("tok");
        props.getSms().setUrl("http://sms.example.org/send");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).available()).isFalse();
        props.getSms().setUrl("https://user:pw@sms.example.org/send");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).available()).isFalse();
        props.getSms().setUrl("not a url");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).available()).isFalse();
        props.getSms().setUrl("https://sms.example.org/send");
        props.getSms().setToken(" ");
        ProviderStatus s = new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).status();
        assertThat(s.problems()).anyMatch(x -> x.contains("token"));
        props.getSms().setToken("tok");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).available()).isTrue();
        props.getSms().setUrl("http://localhost:9/send");
        assertThat(new ProviderRegistry(props, mail(null)).forChannel(DeliveryChannel.sms).available()).isTrue();
    }

    @Test
    void statusNeverExposesSecrets() {
        NotificationProperties props = new NotificationProperties();
        props.getSms().setEnabled(true);
        props.getSms().setUrl("https://sms.example.org/send?key=SECRETVALUE");
        props.getSms().setToken("SECRETTOKEN");
        String text = new ProviderRegistry(props, mail(null)).statuses().toString();
        assertThat(text).doesNotContain("SECRETTOKEN").doesNotContain("SECRETVALUE");
    }

    @Test
    void backoffAndMaxAttempts() {
        NotificationProperties p = new NotificationProperties();
        assertThat(p.effectiveMaxAttempts()).isEqualTo(6);
        assertThat(p.backoffAfter(1)).hasMinutes(1);
        assertThat(p.backoffAfter(2)).hasMinutes(5);
        assertThat(p.backoffAfter(3)).hasMinutes(30);
        assertThat(p.backoffAfter(4)).hasHours(2);
        assertThat(p.backoffAfter(5)).hasHours(6);
        assertThat(p.backoffAfter(6)).isNull();
        p.setMaxAttempts(2);
        assertThat(p.backoffAfter(1)).isNotNull();
        assertThat(p.backoffAfter(2)).isNull();
    }
}
