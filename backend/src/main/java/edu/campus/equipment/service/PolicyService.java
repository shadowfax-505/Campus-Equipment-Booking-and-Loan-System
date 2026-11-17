package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.BorrowingPolicy;
import edu.campus.equipment.domain.CreditScoreConfig;
import edu.campus.equipment.repo.BorrowingPolicyRepository;
import edu.campus.equipment.repo.CreditScoreConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {
    private final BorrowingPolicyRepository policies;
    private final CreditScoreConfigRepository scoreConfigs;

    public PolicyService(BorrowingPolicyRepository policies, CreditScoreConfigRepository scoreConfigs) {
        this.policies = policies;
        this.scoreConfigs = scoreConfigs;
    }

    /** The default policy holds system-wide rules such as the per-student booking limit. */
    public BorrowingPolicy defaultPolicy() {
        return policies.findFirstByDefaultPolicyTrue()
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "NO_DEFAULT_POLICY", "No default borrowing policy is configured"));
    }

    public CreditScoreConfig scoreConfig() {
        return scoreConfigs.findById(1L).orElseGet(() -> scoreConfigs.save(new CreditScoreConfig()));
    }
}
