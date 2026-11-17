package edu.campus.equipment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * State pattern for reservations. Each state declares which states it may move to;
 * {@link #transitionTo} is the single gate through which every status change passes.
 *
 * Requested(PENDING) -> Approved -> Fulfilled, with Rejected, Cancelled, Expired and Waitlisted as alternatives.
 */
public enum ReservationStatus {
    PENDING {
        @Override Set<ReservationStatus> next() { return EnumSet.of(APPROVED, REJECTED, CANCELLED, WAITLISTED, EXPIRED); }
    },
    WAITLISTED {
        @Override Set<ReservationStatus> next() { return EnumSet.of(PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED); }
    },
    APPROVED {
        @Override Set<ReservationStatus> next() { return EnumSet.of(FULFILLED, CANCELLED, EXPIRED); }
        @Override public boolean blocksAvailability() { return true; }
    },
    FULFILLED {
        @Override Set<ReservationStatus> next() { return EnumSet.noneOf(ReservationStatus.class); }
    },
    REJECTED {
        @Override Set<ReservationStatus> next() { return EnumSet.noneOf(ReservationStatus.class); }
    },
    CANCELLED {
        @Override Set<ReservationStatus> next() { return EnumSet.noneOf(ReservationStatus.class); }
    },
    EXPIRED {
        @Override Set<ReservationStatus> next() { return EnumSet.noneOf(ReservationStatus.class); }
    };

    abstract Set<ReservationStatus> next();

    /** Only approved (not yet collected) reservations hold the item; a collected one is represented by its loan. */
    public boolean blocksAvailability() { return false; }

    public boolean isTerminal() { return next().isEmpty(); }

    public boolean isOpenRequest() { return this == PENDING || this == WAITLISTED; }

    public ReservationStatus transitionTo(ReservationStatus target) {
        if (!next().contains(target)) {
            throw new InvalidStateTransitionException("Reservation", name(), target.name());
        }
        return target;
    }
}
