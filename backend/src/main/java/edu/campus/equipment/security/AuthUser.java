package edu.campus.equipment.security;

import edu.campus.equipment.domain.Role;

/** Principal placed in the security context from a verified access token. */
public record AuthUser(Long id, String email, Role role) {}
