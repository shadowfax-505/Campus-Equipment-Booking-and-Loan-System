package edu.campus.equipment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * State pattern for loans: Active -> Returned (or Lost). "Overdue" is derived from the due time
 * and whether the loan is still open, so it is not a stored state.
 */
public enum LoanStatus {
    ACTIVE {
        @Override Set<LoanStatus> next() { return EnumSet.of(RETURNED, LOST); }
    },
    RETURNED {
        @Override Set<LoanStatus> next() { return EnumSet.noneOf(LoanStatus.class); }
    },
    LOST {
        @Override Set<LoanStatus> next() { return EnumSet.noneOf(LoanStatus.class); }
    };

    abstract Set<LoanStatus> next();

    public LoanStatus transitionTo(LoanStatus target) {
        if (!next().contains(target)) {
            throw new InvalidStateTransitionException("Loan", name(), target.name());
        }
        return target;
    }
}
