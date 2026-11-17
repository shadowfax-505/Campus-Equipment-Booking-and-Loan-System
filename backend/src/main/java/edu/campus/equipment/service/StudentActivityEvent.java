package edu.campus.equipment.service;

import edu.campus.equipment.domain.ScoreEventType;

/**
 * Published whenever a verified event changes a student's borrowing record (return, fine, payment, expiry...).
 * Handled synchronously inside the publishing transaction so the score and the financial record always commit together.
 */
public record StudentActivityEvent(Long studentId, ScoreEventType type, String reason,
                                   Long loanId, Long reservationId, Long fineId) {
    public static StudentActivityEvent of(Long studentId, ScoreEventType type, String reason) {
        return new StudentActivityEvent(studentId, type, reason, null, null, null);
    }
}
