package edu.campus.equipment.web;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.CategoryRepository;
import edu.campus.equipment.repo.EquipmentRepository;
import edu.campus.equipment.repo.LocationRepository;
import edu.campus.equipment.security.CurrentUser;
import edu.campus.equipment.service.AvailabilityService;
import edu.campus.equipment.web.dto.Views.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Equipment catalogue")
public class CatalogController {
    private final EquipmentRepository equipment;
    private final CategoryRepository categories;
    private final LocationRepository locations;
    private final AvailabilityService availability;
    private final ViewMapper map;
    private final CurrentUser current;
    private final Clock clock;

    public CatalogController(EquipmentRepository equipment, CategoryRepository categories, LocationRepository locations,
                             AvailabilityService availability, ViewMapper map, CurrentUser current, Clock clock) {
        this.equipment = equipment;
        this.categories = categories;
        this.locations = locations;
        this.availability = availability;
        this.map = map;
        this.current = current;
        this.clock = clock;
    }

    @GetMapping("/equipment")
    @Operation(summary = "Search equipment", description = "Filter by text, category, location, condition, status and availability. "
            + "With 'from' and 'to', 'available=true' keeps items free for that whole period; without them it means free right now.")
    public List<EquipmentView> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId,
                                      @RequestParam(required = false) Long locationId, @RequestParam(required = false) EquipmentCondition condition,
                                      @RequestParam(required = false) OperationalStatus status, @RequestParam(required = false) Boolean available,
                                      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        User u = current.get();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        boolean student = u.getRole() == Role.STUDENT;
        return equipment.search(categoryId, locationId, condition, status, like).stream()
                .filter(e -> !student || e.getStatus() != OperationalStatus.RETIRED)
                .filter(e -> available == null || available == (from != null && to != null
                        ? availability.isAvailable(e, from, to, null, null) : availability.isAvailableNow(e)))
                .map(map::equipment).toList();
    }

    @GetMapping("/equipment/{id}")
    public EquipmentView get(@PathVariable Long id) {
        return map.equipment(equipment.findById(id).orElseThrow(() -> ApiException.notFound("Equipment")));
    }

    @GetMapping("/equipment/{id}/availability")
    @Operation(summary = "Busy periods for an item", description = "Approved bookings, loans and maintenance. Students see periods only, not who holds them.")
    public List<BlockView> availability(@PathVariable Long id, @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to) {
        User u = current.get();
        Equipment e = equipment.findById(id).orElseThrow(() -> ApiException.notFound("Equipment"));
        Instant f = from == null ? clock.instant() : from;
        Instant t = to == null ? f.plus(Duration.ofDays(e.getPolicy().getMaxAdvanceDays())) : to;
        boolean staff = u.getRole() != Role.STUDENT;
        return availability.blocks(e, f, t, null, null).stream().map(b -> BlockView.of(b, staff)).toList();
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return categories.findAll().stream().map(CategoryView::of).toList();
    }

    @GetMapping("/locations")
    public List<LocationView> locations() {
        return locations.findAll().stream().map(LocationView::of).toList();
    }
}
