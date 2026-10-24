package com.smms.notify;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmtpEmailProviderTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SmtpEmailProvider provider = new SmtpEmailProvider(sender, "noreply@example.org", "[SMMS] ");

    private OutboundMessage msg(String to, String subject) {
        return new OutboundMessage(DeliveryChannel.email, to, subject, "body");
    }

    @Test
    void sendsThroughJavaMailWithPrefixedSingleLineSubject() throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        SendResult r = provider.send(msg("user@example.org", "New ticket\r\nBcc: evil@example.org"));
        assertThat(r.accepted()).isTrue();
        ArgumentCaptor<MimeMessage> c = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(c.capture());
        assertThat(c.getValue().getSubject()).isEqualTo("[SMMS] New ticket Bcc: evil@example.org");
        assertThat(c.getValue().getHeader("Bcc")).isNull();
        assertThat(c.getValue().getAllRecipients()).hasSize(1);
    }

    @Test
    void invalidAddressIsPermanentAndNothingIsSent() {
        for (String bad : new String[] {"nope", "a@b", "a@b.co, c@d.co", "a@b.co\r\nBcc:x@y.co", null}) {
            SendResult r = provider.send(msg(bad, "s"));
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
        }
        verifyNoInteractions(sender);
    }

    @Test
    void connectionProblemsAreRetryableAuthProblemsAreNot() {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("connect failed")).when(sender).send(any(MimeMessage.class));
        SendResult t = provider.send(msg("user@example.org", "s"));
        assertThat(t.accepted()).isFalse();
        assertThat(t.retryable()).isTrue();
        reset(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailAuthenticationException("bad credentials")).when(sender).send(any(MimeMessage.class));
        SendResult p = provider.send(msg("user@example.org", "s"));
        assertThat(p.retryable()).isFalse();
    }
}
