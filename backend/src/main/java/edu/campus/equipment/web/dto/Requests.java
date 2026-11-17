package edu.campus.equipment.web.dto;

import edu.campus.equipment.domain.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Validated request bodies. */
public final class Requests {
    private Requests() {}

    public record Register(@NotBlank @Email String email, @NotBlank String password, @NotBlank @Size(max = 120) String fullName,
                           @Size(max = 40) String institutionId, @Size(max = 120) String department, @Size(max = 40) String phone) {}

    public record Login(@NotBlank String email, @NotBlank String password) {}

    public record Refresh(@NotBlank String refreshToken) {}

    public record UpdateProfile(@Size(max = 40) String phone, @Size(max = 120) String department) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record CreateReservation(@NotNull Long equipmentId, @NotNull Instant startAt, @NotNull Instant endAt, @Size(max = 500) String purpose) {}

    public record Reason(@Size(max = 1000) String reason) {}

    public record Approve(boolean override, @Size(max = 1000) String reason) {}

    public record Issue(@NotNull EquipmentCondition condition, Set<String> accessoriesPresent, @Size(max = 2000) String notes) {}

    public record Return(Instant returnedAt, @NotNull EquipmentCondition condition, Set<String> accessoriesPresent,
                         boolean damageVerified, boolean requiresRepair, @Size(max = 2000) String notes, List<String> photoUrls) {}

    public record Charge(@NotNull FineType type, @NotNull @Positive BigDecimal amount, @NotBlank @Size(max = 1000) String assessment) {}

    public record Lost(@PositiveOrZero BigDecimal charge, @NotBlank String notes) {}

    public record ExtensionReq(@NotNull Instant requestedDueAt, @Size(max = 500) String reason) {}

    public record ExtensionDecision(boolean grantException, @Size(max = 1000) String reason) {}

    public record OnlinePayment(@NotNull @Positive BigDecimal amount, List<Long> fineIds) {}

    public record ExternalPayment(@NotNull @Positive BigDecimal amount, List<Long> fineIds, @NotBlank @Size(max = 100) String receiptReference,
                                  @Size(max = 1000) String notes, @Size(max = 500) String evidenceUrl) {}

    public record VerifyPayment(boolean approve, @Size(max = 500) String note) {}

    public record ProviderCallback(@NotBlank String reference, @NotBlank String status, BigDecimal amount, String failureReason) {}

    public record Maintenance(@NotBlank @Size(max = 1000) String reason, Instant startAt, Instant endAt) {}

    public record CompleteMaintenance(@Size(max = 2000) String notes, EquipmentCondition condition) {}

    public record OpenDispute(@NotNull DisputeType type, @NotNull Long subjectId, @NotBlank @Size(max = 2000) String description) {}

    public record ResolveDispute(boolean upheld, @NotBlank @Size(max = 2000) String resolution, BigDecimal correctedAmount,
                                 boolean waiveRelatedCharges) {}

    public record ScoreAdjust(double points, @NotBlank @Size(max = 1000) String reason) {}

    public record FineCorrection(@NotNull @PositiveOrZero BigDecimal amount, @NotBlank String reason) {}

    public record StatusChange(@NotNull OperationalStatus status, @NotBlank String reason) {}

    public record CategoryReq(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description) {}

    public record LocationReq(@NotBlank @Size(max = 120) String name, String department, String room) {}
}
