package com.smms.service;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Dashboard counters. Every count is computed inside the actor's work-order scope. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderAccessPolicy accessPolicy;

    public Map<String, Object> controlRoomStats(User actor) {
        // The scope already covers all of the operator's control rooms in their center
        return statsByStatus(accessPolicy.resolveScope(actor), null);
    }

    public Map<String, Object> technicianStats(User actor) {
        // Technician scope is "assigned to me", so no extra assignee filter is needed
        WorkOrderScope scope = accessPolicy.resolveScope(actor);
        Specification<WorkOrder> base = accessPolicy.specFor(scope);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("assigned",          count(scope, base, TicketStatus.ASSIGNED, null));
        stats.put("in_progress",       count(scope, base, TicketStatus.IN_PROGRESS, null));
        stats.put("waiting_materials", count(scope, base, TicketStatus.WAITING_MATERIALS, null));
        stats.put("completed",         count(scope, base, TicketStatus.COMPLETED, null));
        return stats;
    }

    public Map<String, Object> managementStats(User actor, UUID centerId) {
        // centerId is a caller filter: it can only narrow the actor's scope
        return statsByStatus(accessPolicy.resolveScope(actor), centerId);
    }

    private Map<String, Object> statsByStatus(WorkOrderScope scope, UUID centerId) {
        Specification<WorkOrder> base = accessPolicy.specFor(scope);
        Map<String, Object> stats = new LinkedHashMap<>();
        for (TicketStatus s : TicketStatus.values()) {
            stats.put(s.getDbValue(), count(scope, base, s, centerId));
        }
        return stats;
    }

    private long count(WorkOrderScope scope, Specification<WorkOrder> base, TicketStatus status, UUID centerId) {
        // No visibility: zero without querying
        if (scope.isNone()) return 0L;
        Specification<WorkOrder> filters = (root, query, cb) -> centerId == null
            ? cb.equal(root.get("status"), status)
            : cb.and(cb.equal(root.get("status"), status), cb.equal(root.get("center").get("id"), centerId));
        return workOrderRepo.count(base.and(filters));
    }
}
