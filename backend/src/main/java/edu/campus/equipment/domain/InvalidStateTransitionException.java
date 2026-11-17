package edu.campus.equipment.domain;

import edu.campus.equipment.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends ApiException {
    public InvalidStateTransitionException(String entity, String from, String to) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", entity + " cannot move from " + from + " to " + to);
    }
}
