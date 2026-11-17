package edu.campus.equipment.domain;

public enum DisputeStatus { OPEN, UNDER_REVIEW, UPHELD, DISMISSED;
    public boolean isOpen() { return this == OPEN || this == UNDER_REVIEW; }
}
