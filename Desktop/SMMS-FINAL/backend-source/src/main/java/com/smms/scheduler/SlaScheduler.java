package com.smms.scheduler;

import com.smms.enums.TicketStatus;
import com.smms.entity.WorkOrder;
import com.smms.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlaScheduler {

    private final WorkOrderRepository workOrderRepo;

    private static final List<String> TERMINAL = List.of(
        TicketStatus.COMPLETED.getDbValue(),
        TicketStatus.CLOSED.getDbValue(),
        TicketStatus.CANCELLED.getDbValue()
    );

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void checkBreaches() {
        List<WorkOrder> breached = workOrderRepo.findSlaBreached(Instant.now(), TERMINAL);
        if (breached.isEmpty()) return;

        List<java.util.UUID> ids = breached.stream().map(WorkOrder::getId).toList();
        workOrderRepo.markSlaBreached(ids);
        log.info("SLA breached for {} tickets", ids.size());
    }
}
