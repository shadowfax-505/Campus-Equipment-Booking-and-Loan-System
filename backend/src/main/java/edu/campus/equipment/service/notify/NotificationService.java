package edu.campus.equipment.service.notify;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.NotificationRepository;
import edu.campus.equipment.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-app notifications double as an email outbox: each row is written in the business transaction
 * and a scheduled job delivers pending emails with retries, so a mail outage never rolls back a return or payment.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationRepository repo;
    private final UserRepository users;
    private final EmailSender email;
    private final Clock clock;
    private final DateTimeFormatter fmt;

    public NotificationService(NotificationRepository repo, UserRepository users, EmailSender email, Clock clock, ZoneId campusZone) {
        this.repo = repo;
        this.users = users;
        this.email = email;
        this.clock = clock;
        this.fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z").withZone(campusZone);
    }

    /** Formats an instant in the campus timezone for messages. */
    public String at(Instant t) {
        return t == null ? "-" : fmt.format(t);
    }

    public Notification notify(User user, NotificationType type, String title, String message) {
        Notification n = new Notification();
        n.setUser(user);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setCreatedAt(clock.instant());
        return repo.save(n);
    }

    /** Notifies staff responsible for the item's location, falling back to administrators if none are assigned. */
    public void notifyStaff(Equipment equipment, NotificationType type, String title, String message) {
        Set<User> targets = new HashSet<>(users.findStaffForLocation(equipment.getLocation().getId()));
        if (targets.isEmpty()) targets.addAll(users.findByRole(Role.ADMIN));
        targets.stream().filter(u -> u.getStatus() == AccountStatus.ACTIVE).forEach(u -> notify(u, type, title, message));
    }

    @Transactional
    public int deliverPendingEmails() {
        List<Notification> batch = repo.findTop50ByEmailStatusAndEmailAttemptsLessThanOrderByCreatedAt(DeliveryStatus.PENDING, MAX_ATTEMPTS);
        int sent = 0;
        for (Notification n : batch) {
            n.setEmailAttempts(n.getEmailAttempts() + 1);
            try {
                email.send(n.getUser().getEmail(), n.getTitle(), n.getMessage());
                n.setEmailStatus(DeliveryStatus.SENT);
                n.setEmailError(null);
                sent++;
            } catch (Exception e) {
                log.warn("Email delivery failed for notification {}: {}", n.getId(), e.getMessage());
                n.setEmailError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (n.getEmailAttempts() >= MAX_ATTEMPTS) n.setEmailStatus(DeliveryStatus.FAILED);
            }
        }
        return sent;
    }
}
