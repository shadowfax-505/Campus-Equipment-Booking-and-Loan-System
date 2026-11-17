package edu.campus.equipment.domain;

public enum FineStatus {
    OUTSTANDING, PARTIALLY_PAID, PAID, WAIVED, CANCELLED;

    /** A fine still owes money and can block borrowing. */
    public boolean isPayable() { return this == OUTSTANDING || this == PARTIALLY_PAID; }
}
