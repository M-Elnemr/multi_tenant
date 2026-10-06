package com.platform.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends through SMTP when app.mail.enabled=true (spring.mail.* configured); otherwise logs, so dev needs no credentials. */
@Component
public class EmailChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final boolean enabled;
    private final String from;

    public EmailChannel(ObjectProvider<JavaMailSender> mail, @Value("${app.mail.enabled:false}") boolean enabled, @Value("${app.mail.from:no-reply@platform.local}") String from) {
        this.mail = mail;
        this.enabled = enabled;
        this.from = from;
    }

    @Override public String code() { return "EMAIL"; }

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender sender = enabled ? mail.getIfAvailable() : null;
        if (sender == null) {
            log.info("[email disabled] to={} subject={}", to, subject);
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        sender.send(m);
    }
}
