package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.Equipment;
import edu.campus.equipment.domain.Role;
import edu.campus.equipment.domain.User;
import org.springframework.stereotype.Component;

/** Lab staff act only on equipment at the locations they are responsible for; administrators act everywhere. */
@Component
public class StaffScope {
    public boolean covers(User staff, Equipment e) {
        if (staff.getRole() == Role.ADMIN) return true;
        if (staff.getRole() != Role.STAFF) return false;
        return staff.getManagedLocations().stream().anyMatch(l -> l.getId().equals(e.getLocation().getId()));
    }

    public void require(User staff, Equipment e) {
        if (!covers(staff, e)) {
            throw ApiException.forbidden("Equipment at " + e.getLocation().getName() + " is not under your responsibility");
        }
    }
}
