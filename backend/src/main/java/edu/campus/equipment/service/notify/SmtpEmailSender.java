package edu.campus.equipment.service.notify;

import edu.campus.equipment.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP adapter, enabled with app.mail.provider=smtp and the standard spring.mail.* settings. */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail, AppProperties props) {
        this.mail = mail;
        this.from = props.mail().from();
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        mail.send(m);
    }
}
