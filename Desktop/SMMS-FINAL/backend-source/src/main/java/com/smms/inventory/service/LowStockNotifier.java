package com.smms.inventory.service;

import com.smms.entity.InventoryItem;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.inventory.InventoryProperties;
import com.smms.inventory.repo.InventoryItemRepository;
import com.smms.repository.UserRepository;
import com.smms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * After the stock transaction commits: re-arms the alert of items that are above their reorder level
 * again and, once per drop below it, tells the warehouse and maintenance-manager users of the center.
 * The claim is one atomic UPDATE, so concurrent commits notify once. A failure here never affects the stock change.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LowStockNotifier {

    private final InventoryItemRepository items;
    private final UserRepository users;
    private final NotificationService notifications;
    private final PlatformTransactionManager txManager;
    private final InventoryProperties props;

    /**
     * Runs on its own thread: inside afterCommit the committing connection is still held, so opening a
     * second transaction there would need two pool connections per request and can exhaust the pool.
     */
    private final java.util.concurrent.ExecutorService worker = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1000),
        r -> { Thread t = new Thread(r, "low-stock-notifier"); t.setDaemon(true); return t; },
        (r, ex) -> log.warn("Low-stock check queue is full, check skipped"));

    @jakarta.annotation.PreDestroy
    void shutdown() { worker.shutdown(); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockChanged(StockChangedEvent e) {
        if (!props.isLowStockNotifications()) return;
        worker.execute(() -> check(e));
    }

    void check(StockChangedEvent e) {
        for (UUID itemId : e.itemIds()) {
            try {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(s -> process(itemId));
            } catch (RuntimeException ex) {
                log.warn("Low-stock check failed for item {}: {}", itemId, ex.getClass().getSimpleName());
            }
        }
    }

    private void process(UUID itemId) {
        items.rearmLowStock(itemId);
        if (items.claimLowStock(itemId) != 1) return;
        InventoryItem item = items.findById(itemId).orElse(null);
        if (item == null) return;
        String title = "Low stock: " + item.getSku() + " " + item.getName();
        int n = 0;
        for (UserRole role : new UserRole[] {UserRole.warehouse, UserRole.maintenance_manager}) {
            for (User u : users.findAllByCenterIdAndRoleAndDeletedAtIsNull(item.getCenter().getId(), role)) {
                if (!u.isActive() || !Objects.equals(u.getTenantId(), item.getTenantId())) continue;
                notifications.notifyUser(u, null, "LOW_STOCK", title);
                n++;
            }
        }
        log.info("AUDIT low stock: item={} notified={}", itemId, n);
    }
}
