package edu.campus.equipment.service;

import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.ScoreEventType;
import edu.campus.equipment.service.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Background processing. Every job is idempotent (state checks and "sent-at" markers), so overlapping or
 * retried runs never double-notify, double-expire or double-charge.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final ReservationService reservations;
    private final LoanService loans;
    private final MaintenanceService maintenance;
    private final NotificationService notifications;
    private final CreditScoreService scores;
    private final Duration lead;

    public ScheduledJobs(ReservationService reservations, LoanService loans, MaintenanceService maintenance,
                         NotificationService notifications, CreditScoreService scores, AppProperties props) {
        this.reservations = reservations;
        this.loans = loans;
        this.maintenance = maintenance;
        this.notifications = notifications;
        this.scores = scores;
        this.lead = props.jobs() == null || props.jobs().reminderLead() == null ? Duration.ofHours(24) : props.jobs().reminderLead();
    }

    @Scheduled(fixedDelayString = "${app.jobs.expiry-interval:PT1M}")
    public void expireReservations() {
        run("expiry", reservations::expireOverdue);
    }

    @Scheduled(fixedDelayString = "${app.jobs.overdue-interval:PT5M}")
    public void overdueChecks() {
        run("overdue", loans::notifyOverdue);
    }

    @Scheduled(fixedDelayString = "${app.jobs.reminder-interval:PT15M}")
    public void reminders() {
        run("return-reminders", () -> loans.sendReturnReminders(lead));
        run("collection-reminders", () -> loans.sendCollectionReminders(lead));
        run("maintenance-activation", maintenance::activateScheduled);
    }

    @Scheduled(fixedDelayString = "${app.jobs.email-interval:PT30S}")
    public void emails() {
        run("email-outbox", notifications::deliverPendingEmails);
    }

    /** Nightly: lets old events fall out of the recent-history period and counts fines that became overdue for payment. */
    @Scheduled(cron = "${app.jobs.score-cron:0 30 2 * * *}", zone = "${app.timezone:UTC}")
    public void nightlyScores() {
        run("score-recalculation", () -> scores.recalculateAll(ScoreEventType.RECALCULATION, "Periodic recalculation"));
    }

    private void run(String name, java.util.function.IntSupplier job) {
        try {
            int n = job.getAsInt();
            if (n > 0) log.info("Job {} processed {} item(s)", name, n);
        } catch (Exception e) {
            log.error("Job {} failed; it will be retried on the next run", name, e);
        }
    }
}
