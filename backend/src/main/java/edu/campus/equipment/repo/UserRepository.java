package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByInstitutionId(String institutionId);
    List<User> findByRole(Role role);

    @Query("select u from User u where (:role is null or u.role = :role) and (:q is null or lower(u.fullName) like :q or lower(u.email) like :q or lower(u.institutionId) like :q) order by u.fullName")
    Page<User> search(@Param("role") Role role, @Param("q") String q, Pageable pageable);

    @Query("select distinct u from User u join u.managedLocations l where u.role = edu.campus.equipment.domain.Role.STAFF and l.id = :locationId")
    List<User> findStaffForLocation(@Param("locationId") Long locationId);
}
