package edu.campus.equipment.service.policy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Highest score first; ties go to the earliest submitted request (then lowest id for full determinism). */
@Component
public class CreditPriorityAllocationStrategy implements AllocationStrategy {

    public static final Comparator<Candidate> ORDER = Comparator
            .comparingDouble(Candidate::score).reversed()
            .thenComparing(Candidate::submittedAt)
            .thenComparing(Candidate::reservationId);

    @Override
    public List<Ranked> rank(List<Candidate> candidates) {
        List<Ranked> out = new ArrayList<>();
        int rank = 1;
        for (Candidate c : candidates.stream().filter(Candidate::eligible).sorted(ORDER).toList()) {
            out.add(new Ranked(c, rank++));
        }
        candidates.stream().filter(c -> !c.eligible()).sorted(ORDER).forEach(c -> out.add(new Ranked(c, null)));
        return out;
    }

    @Override
    public String describe() {
        return "Eligible requests ranked by credit score (highest first); ties broken by earliest submission";
    }
}
