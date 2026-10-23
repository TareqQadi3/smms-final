package com.smms.authz;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Role x ticket-position visibility table for WorkOrderAccessPolicy.resolveScope. The scope is
 * evaluated in memory by {@link #visible} (the documented semantics of WorkOrderScope: ANY of
 * requested-by, assigned-to or the location grant, where a location grant needs ALL its parts).
 * Tenant isolation is the policy's tenant predicate and is covered by WorkOrderAccessPolicyTest;
 * a different tenant sits on the same row positions as another center, so it adds no new branch.
 *
 * Positions of the ticket relative to the actor: OWN (submitted by the actor, other center),
 * ASSIGNED (assigned to the actor, other center), SAME_CENTER (someone else's, in an assigned room),
 * SAME_CENTER_OTHER_ROOM, SAME_CENTER_MATERIALS, OTHER_CENTER (same region), OTHER_REGION.
 */
class WorkOrderVisibilityMatrixTest {

    private final UUID region1 = UUID.randomUUID();
    private final UUID center1 = UUID.randomUUID();
    private final UUID roomAssigned = UUID.randomUUID();
    private final UUID roomOther = UUID.randomUUID();
    private UserControlRoomAccessRepository accessRepo;
    private WorkOrderAccessPolicy policy;

    @BeforeEach
    void setUp() {
        accessRepo = mock(UserControlRoomAccessRepository.class);
        policy = new WorkOrderAccessPolicy(mock(WorkOrderRepository.class), accessRepo);
    }

    private record Ticket(UUID requester, UUID assignee, UUID center, UUID region, UUID room, boolean needsMaterials) {}

    private static boolean visible(WorkOrderScope s, Ticket t) {
        if (s.unrestricted()) return true;
        if (s.requestedById() != null && s.requestedById().equals(t.requester())) return true;
        if (s.assignedToId() != null && s.assignedToId().equals(t.assignee())) return true;
        WorkOrderScope.Location l = s.location();
        if (l == null) return false;
        if (l.centerId() != null && !l.centerId().equals(t.center())) return false;
        if (l.regionId() != null && !l.regionId().equals(t.region())) return false;
        if (l.controlRoomIds() != null && !l.controlRoomIds().contains(t.room())) return false;
        return !l.materialsOnly() || t.needsMaterials();
    }

    private User actor(UserRole role, boolean withCenter, boolean withRegion) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        if (withCenter) { Center c = new Center(); c.setId(center1); u.setCenter(c); }
        if (withRegion) { Region r = new Region(); r.setId(region1); u.setRegion(r); }
        return u;
    }

    /** Positions in a fixed order: OWN, ASSIGNED, SAME_CENTER, SAME_CENTER_OTHER_ROOM, SAME_CENTER_MATERIALS, OTHER_CENTER, OTHER_REGION. */
    private List<Ticket> positions(User a) {
        UUID stranger = UUID.randomUUID();
        UUID otherCenter = UUID.randomUUID();
        UUID otherRegion = UUID.randomUUID();
        return List.of(
            new Ticket(a.getId(), null, otherCenter, region1, UUID.randomUUID(), false),             // OWN
            new Ticket(stranger, a.getId(), otherCenter, region1, UUID.randomUUID(), false),         // ASSIGNED
            new Ticket(stranger, null, center1, region1, roomAssigned, false),                       // SAME_CENTER
            new Ticket(stranger, null, center1, region1, roomOther, false),                          // SAME_CENTER_OTHER_ROOM
            new Ticket(stranger, null, center1, region1, roomAssigned, true),                        // SAME_CENTER_MATERIALS
            new Ticket(stranger, null, otherCenter, region1, UUID.randomUUID(), true),               // OTHER_CENTER
            new Ticket(stranger, null, UUID.randomUUID(), otherRegion, UUID.randomUUID(), true));    // OTHER_REGION
    }

    private static final String[] POS = {"OWN", "ASSIGNED", "SAME_CENTER", "SAME_CENTER_OTHER_ROOM",
        "SAME_CENTER_MATERIALS", "OTHER_CENTER", "OTHER_REGION"};

    /** Expected visibility, same order as POS. */
    private static boolean[] row(boolean... v) { return v; }

    private void check(UserRole role, boolean[] expected) {
        User a = actor(role, true, role == UserRole.region_supervisor);
        when(accessRepo.findControlRoomIdsByUserId(any())).thenReturn(List.of(roomAssigned));
        WorkOrderScope scope = policy.resolveScope(a);
        List<Ticket> ts = positions(a);
        List<String> diff = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) {
            if (visible(scope, ts.get(i)) != expected[i]) {
                diff.add(role + " at " + POS[i] + " expected " + (expected[i] ? "visible" : "hidden"));
            }
        }
        assertThat(diff).isEmpty();
    }

    @Test
    void technicianSeesOnlyWhatIsAssignedToThem() {
        check(UserRole.technician, row(false, true, false, false, false, false, false));
    }

    @Test
    void residentAndDepartmentSeeOnlyTheirOwnSubmissions() {
        check(UserRole.resident, row(true, false, false, false, false, false, false));
        check(UserRole.department, row(true, false, false, false, false, false, false));
    }

    @Test
    void controlRoomNeedsBothTheCenterAndAnAssignedRoom() {
        check(UserRole.control_room, row(true, false, true, false, true, false, false));
    }

    @Test
    void centerManagersSeeTheirCenterNotOthers() {
        for (UserRole r : List.of(UserRole.hospital_supervisor, UserRole.maintenance_manager,
                UserRole.electrical_manager, UserRole.operating_company_manager)) {
            check(r, row(true, false, true, true, true, false, false));
        }
    }

    @Test
    void warehouseSeesOnlyMaterialsTicketsOfItsCenter() {
        check(UserRole.warehouse, row(false, false, false, false, true, false, false));
    }

    @Test
    void regionSupervisorSeesTheWholeRegionAndNothingOutsideIt() {
        check(UserRole.region_supervisor, row(true, true, true, true, true, true, false));
    }

    @Test
    void otherRoleSeesNothingAndSystemAdminSeesAll() {
        check(UserRole.other, row(false, false, false, false, false, false, false));
        check(UserRole.system_admin, row(true, true, true, true, true, true, true));
    }

    @Test
    void missingScopeDataNeverWidensAccess() {
        // Same roles without their center/region link: only the actor-bound branches remain
        when(accessRepo.findControlRoomIdsByUserId(any())).thenReturn(List.of(roomAssigned));
        for (UserRole role : UserRole.values()) {
            if (role == UserRole.system_admin) continue;
            User a = actor(role, false, false);
            WorkOrderScope scope = policy.resolveScope(a);
            for (Ticket t : positions(a)) {
                boolean viaLocation = visible(scope, t)
                    && !(a.getId().equals(t.requester()) || a.getId().equals(t.assignee()));
                assertThat(viaLocation).as("%s without scope data must not see foreign tickets", role).isFalse();
            }
        }
    }

    @Test
    void everyRoleIsInTheMatrix() {
        Set<UserRole> covered = Set.of(UserRole.technician, UserRole.resident, UserRole.department,
            UserRole.control_room, UserRole.hospital_supervisor, UserRole.maintenance_manager,
            UserRole.electrical_manager, UserRole.operating_company_manager, UserRole.warehouse,
            UserRole.region_supervisor, UserRole.other, UserRole.system_admin);
        assertThat(covered).containsExactlyInAnyOrder(UserRole.values());
    }
}
