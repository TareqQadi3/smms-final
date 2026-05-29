package com.smms.service;

import com.smms.entity.User;
import com.smms.enums.*;
import com.smms.repository.*;
import jakarta.persistence.criteria.Predicate;
import lombok.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final WorkOrderRepository workOrderRepo;
    private final UserControlRoomAccessRepository accessRepo;

    private static final List<TicketStatus> TERMINAL = List.of(
        TicketStatus.COMPLETED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    public Map<String, Object> controlRoomStats(User actor) {
        List<UUID> crIds = accessRepo.findByUserId(actor.getId())
            .stream().map(a -> a.getControlRoom().getId()).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        for (TicketStatus s : TicketStatus.values()) {
            UUID crId = crIds.isEmpty() ? null : crIds.get(0);
            long count = countBySpec(null, crId, s, null, null);
            stats.put(s.getDbValue(), count);
        }
        return stats;
    }

    public Map<String, Object> technicianStats(User actor) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("assigned",   countBySpec(null, null, TicketStatus.ASSIGNED,    null, actor.getId()));
        stats.put("inProgress", countBySpec(null, null, TicketStatus.IN_PROGRESS, null, actor.getId()));
        stats.put("completed",  countBySpec(null, null, TicketStatus.COMPLETED,   null, actor.getId()));
        return stats;
    }

    public Map<String, Object> managementStats(User actor, UUID centerId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (TicketStatus s : TicketStatus.values()) {
            stats.put(s.getDbValue(), countBySpec(centerId, null, s, null, null));
        }
        return stats;
    }

    private long countBySpec(UUID centerId, UUID controlRoomId,
                              TicketStatus status, TicketPriority priority,
                              UUID assignedToId) {
        Specification<com.smms.entity.WorkOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (centerId      != null) predicates.add(cb.equal(root.get("center").get("id"),      centerId));
            if (controlRoomId != null) predicates.add(cb.equal(root.get("controlRoom").get("id"), controlRoomId));
            if (status        != null) predicates.add(cb.equal(root.get("status"),                status));
            if (priority      != null) predicates.add(cb.equal(root.get("priority"),              priority));
            if (assignedToId  != null) predicates.add(cb.equal(root.get("assignedTo").get("id"),  assignedToId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return workOrderRepo.count(spec);
    }
}
