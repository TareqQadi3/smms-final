package com.smms.auth;

import com.smms.repository.UploadRequestRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Housekeeping for sessions, idempotency records, tickets and expired sockets. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCleanupJob {

    static final long UPLOAD_REQUEST_RETENTION_DAYS = 7;

    private final AuthSessionService sessions;
    private final UploadRequestRepository uploadRequests;
    private final WsTicketService tickets;
    private final NotificationWebSocketHandler webSockets;

    @Scheduled(cron = "${smms.auth.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void purgeDaily() {
        int s = sessions.purgeExpired();
        int u = uploadRequests.deleteOlderThan(Instant.now().minusSeconds(UPLOAD_REQUEST_RETENTION_DAYS * 86400));
        log.info("Auth cleanup: {} expired sessions and {} old upload requests removed", s, u);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepMemory() {
        tickets.purgeExpired();
        webSockets.closeExpired();
    }
}
