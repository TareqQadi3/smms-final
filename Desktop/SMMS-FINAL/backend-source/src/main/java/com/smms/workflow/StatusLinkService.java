package com.smms.workflow;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.workflow.WorkflowDtos.PublicStatusResponse;
import com.smms.workflow.WorkflowDtos.StatusLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creation (requester only) and public lookup of status links. The token is 32 random bytes
 * (base64url, 43 characters), shown once; only its SHA-256 is stored. Creating a link revokes the
 * previous one. The public answer is the {@link PublicStatusResponse} whitelist and nothing else.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StatusLinkService {

    /** Shape of a valid token: anything else is rejected before touching the database. */
    public static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StatusLinkRepository linkRepo;
    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private final PublicStatusProperties props;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) { this.clock = clock; }

    public StatusLinkResponse create(UUID workOrderId, User actor) {
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);
        if (wo.getRequestedBy() == null || !wo.getRequestedBy().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the requester can create a status link");
        }
        if (!props.isEnabled()) throw new WorkflowException("Public status links are disabled");
        if (wo.getTenantId() == null) throw new WorkflowException("Ticket has no tenant");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Instant expires = now.plus(Math.max(1, props.getLinkTtlDays()), ChronoUnit.DAYS);
        linkRepo.revokeActive(wo.getId(), now);
        try {
            linkRepo.saveAndFlush(StatusLink.builder().tenantId(wo.getTenantId()).workOrderId(wo.getId())
                .tokenHash(hash(token)).createdById(actor.getId()).expiresAt(expires).build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A status link was created concurrently, retry");
        }
        historyWriter.note(wo, actor, "Public status link created");
        return new StatusLinkResponse(token, "/status/" + token, expires);
    }

    /** Empty for anything that is not an active link of a live work order (identical for all causes). */
    @Transactional(readOnly = true)
    public Optional<PublicStatusResponse> lookup(String token) {
        if (!props.isEnabled() || token == null || !TOKEN.matcher(token).matches()) return Optional.empty();
        return linkRepo.findWorkOrderByActiveToken(hash(token), clock.instant()).map(StatusLinkService::toPublic);
    }

    static PublicStatusResponse toPublic(WorkOrder wo) {
        TicketStatus s = wo.getStatus();
        String group = switch (s) {
            case NEW, UNDER_REVIEW -> "received";
            case ASSIGNED, IN_PROGRESS -> "in_progress";
            case WAITING_MATERIALS, WAITING_APPROVAL -> "waiting";
            case COMPLETED, CLOSED -> "done";
            case CANCELLED -> "cancelled";
        };
        // a ticket waiting for the requester's answer is "waiting", not "received"
        if (s == TicketStatus.UNDER_REVIEW && "info_requested".equals(wo.getCurrentStage())) group = "waiting";
        return new PublicStatusResponse(wo.getTicketNumber(), s.getDbValue(), group, wo.getCreatedAt(),
            wo.getUpdatedAt(), s == TicketStatus.COMPLETED || s == TicketStatus.CLOSED);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
