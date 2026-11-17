package edu.campus.equipment.security;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.AccountStatus;
import edu.campus.equipment.domain.Role;
import edu.campus.equipment.domain.StaffPermission;
import edu.campus.equipment.domain.User;
import edu.campus.equipment.repo.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user and centralises role, permission and ownership checks. */
@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public AuthUser principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser u)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        return u;
    }

    /** Loads the user and refuses suspended or deactivated accounts even if their token is still valid. */
    public User get() {
        User u = users.findById(principal().id()).orElseThrow(() -> ApiException.forbidden("Account no longer exists"));
        if (u.getStatus() != AccountStatus.ACTIVE) throw ApiException.forbidden("Account is " + u.getStatus());
        return u;
    }

    public User requireStaff() {
        User u = get();
        if (u.getRole() == Role.STUDENT) throw ApiException.forbidden("Staff access required");
        return u;
    }

    public User requirePermission(StaffPermission p) {
        User u = requireStaff();
        if (!u.hasPermission(p)) throw ApiException.forbidden("Missing permission " + p);
        return u;
    }

    /** Students may only touch their own records; staff and admins may see any (staff are further scoped by location). */
    public void requireSelfOrStaff(User current, Long ownerId) {
        if (current.getRole() == Role.STUDENT && !current.getId().equals(ownerId)) {
            throw ApiException.forbidden("You can only access your own records");
        }
    }
}
