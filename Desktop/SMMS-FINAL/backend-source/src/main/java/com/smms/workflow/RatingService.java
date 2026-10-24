package com.smms.workflow;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.RequesterConfirmation;
import com.smms.enums.TicketStatus;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.InputSanitizer;
import com.smms.workflow.WorkflowDtos.RatingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One rating per work order, only by the requester, when the ticket is closed or completed and
 * accepted, within 30 days of closing/completion. The unique constraint on work_order_id is the
 * duplicate guard under concurrency.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RatingService {

    public static final Duration WINDOW = Duration.ofDays(30);

    private final RatingRepository ratingRepo;
    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) { this.clock = clock; }

    public RatingResponse rate(UUID workOrderId, int score, String comment, User actor) {
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);
        if (wo.getRequestedBy() == null || !wo.getRequestedBy().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the requester can rate the work");
        }
        if (score < 1 || score > 5) throw new IllegalArgumentException("score must be between 1 and 5");
        if (wo.getTenantId() == null) throw new WorkflowException("Ticket has no tenant");
        boolean eligible = wo.getStatus() == TicketStatus.CLOSED
            || (wo.getStatus() == TicketStatus.COMPLETED && wo.getRequesterConfirmation() == RequesterConfirmation.accepted);
        if (!eligible) throw new WorkflowException("Only a closed ticket, or a completed one you accepted, can be rated");
        Instant since = wo.getStatus() == TicketStatus.CLOSED ? wo.getClosedAt() : wo.getCompletedDate();
        if (since != null && clock.instant().isAfter(since.plus(WINDOW))) {
            throw new WorkflowException("The rating period (30 days) has ended");
        }
        if (ratingRepo.existsByWorkOrderId(wo.getId())) throw new ConflictException("This ticket was already rated");
        String clean = InputSanitizer.clean(comment, 1000);
        WorkOrderRating saved;
        try {
            saved = ratingRepo.saveAndFlush(WorkOrderRating.builder().tenantId(wo.getTenantId())
                .workOrderId(wo.getId()).ratedById(actor.getId()).score((short) score)
                .comment(clean == null || clean.isBlank() ? null : clean).build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This ticket was already rated");
        }
        historyWriter.note(wo, actor, "Requester rated the work: " + score + "/5");
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public RatingResponse get(UUID workOrderId, User actor) {
        accessPolicy.requireReadable(workOrderId, actor);
        return ratingRepo.findByWorkOrderId(workOrderId).map(RatingService::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
    }

    private static RatingResponse toResponse(WorkOrderRating r) {
        return new RatingResponse(r.getId(), r.getScore(), r.getComment(), r.getCreatedAt());
    }
}
