package edu.campus.equipment.domain;

/** Fine-grained permissions an administrator can grant to individual lab staff. Administrators hold all of them implicitly. */
public enum StaffPermission {
    /** Verify payments collected outside the integrated provider. */
    VERIFY_PAYMENTS,
    /** Approve a request that is not the top-ranked one, or approve before the decision window closes. */
    OVERRIDE_ALLOCATION,
    /** Grant exceptions, such as an extension for a student with unpaid fines. */
    GRANT_EXCEPTIONS,
    /** Assess damage, missing-accessory and loss charges. */
    ASSESS_CHARGES
}
