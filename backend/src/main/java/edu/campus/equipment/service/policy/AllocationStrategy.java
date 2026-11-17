package edu.campus.equipment.service.policy;

import java.time.Instant;
import java.util.List;

/** Strategy for ordering competing requests for one item. */
public interface AllocationStrategy {

    record Candidate(Long reservationId, Long studentId, double score, Instant submittedAt,
                     boolean eligible, String exclusionReason) {}

    record Ranked(Candidate candidate, Integer rank) {}

    /** Returns every candidate; eligible ones carry rank 1..n, ineligible ones have a null rank and come last. */
    List<Ranked> rank(List<Candidate> candidates);

    String describe();
}
