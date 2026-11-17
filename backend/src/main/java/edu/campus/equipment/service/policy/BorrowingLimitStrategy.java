package edu.campus.equipment.service.policy;

import edu.campus.equipment.domain.BorrowingPolicy;
import edu.campus.equipment.domain.Equipment;
import edu.campus.equipment.common.ApiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Strategy for booking-length and advance-booking limits; the default reads them from the item's policy. */
@Component
public class BorrowingLimitStrategy {

    public void validatePeriod(Equipment equipment, Instant start, Instant end, Instant now) {
        BorrowingPolicy p = equipment.getPolicy();
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_PERIOD", "The end time must be after the start time");
        }
        if (start.isBefore(now.minus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_PERIOD", "The booking cannot start in the past");
        }
        if (start.isAfter(now.plus(Duration.ofDays(p.getMaxAdvanceDays())))) {
            throw ApiException.badRequest("TOO_FAR_AHEAD", "Bookings can be made at most " + p.getMaxAdvanceDays() + " days ahead");
        }
        Duration d = Duration.between(start, end);
        if (d.toMinutes() < p.getMinLoanMinutes()) {
            throw ApiException.badRequest("TOO_SHORT", "The minimum booking length is " + p.getMinLoanMinutes() + " minutes");
        }
        if (d.compareTo(Duration.ofHours(p.getMaxLoanHours())) > 0) {
            throw ApiException.badRequest("TOO_LONG", "The maximum booking length for this item is " + p.getMaxLoanHours() + " hours");
        }
    }

    public void validateExtension(Equipment equipment, Instant issuedAt, Instant requestedDue, int extensionsSoFar) {
        BorrowingPolicy p = equipment.getPolicy();
        if (extensionsSoFar >= p.getMaxExtensions()) {
            throw ApiException.unprocessable("EXTENSION_LIMIT", "This loan already has the maximum of " + p.getMaxExtensions() + " extension(s)");
        }
        if (Duration.between(issuedAt, requestedDue).compareTo(Duration.ofHours(p.getMaxLoanHours())) > 0) {
            throw ApiException.unprocessable("TOO_LONG", "A loan cannot exceed " + p.getMaxLoanHours() + " hours in total, including extensions");
        }
    }
}
