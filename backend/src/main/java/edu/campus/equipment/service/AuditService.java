package edu.campus.equipment.service;

import edu.campus.equipment.domain.AuditLog;
import edu.campus.equipment.domain.User;
import edu.campus.equipment.repo.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Append-only trail of sensitive actions: overrides, waivers, score changes, condition assessments, admin edits. */
@Service
public class AuditService {
    private final AuditLogRepository repo;
    private final Clock clock;

    public AuditService(AuditLogRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public void record(User actor, String action, String entityType, Long entityId, String details) {
        AuditLog a = new AuditLog();
        a.setActor(actor);
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setDetails(details == null ? null : details.length() > 4000 ? details.substring(0, 4000) : details);
        a.setCreatedAt(clock.instant());
        repo.save(a);
    }
}
