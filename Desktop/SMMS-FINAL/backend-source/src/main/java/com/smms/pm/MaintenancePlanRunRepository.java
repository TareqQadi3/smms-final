package com.smms.pm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MaintenancePlanRunRepository extends JpaRepository<MaintenancePlanRun, UUID> {

    List<MaintenancePlanRun> findByWorkOrderIdIn(Collection<UUID> workOrderIds);

    List<MaintenancePlanRun> findTop10ByPlanIdAndTenantIdOrderByDueDateDesc(UUID planId, UUID tenantId);

    Page<MaintenancePlanRun> findByPlanIdAndTenantId(UUID planId, UUID tenantId, Pageable pageable);

    java.util.Optional<MaintenancePlanRun> findByPlanIdAndDueDate(UUID planId, LocalDate dueDate);

    /**
     * Claims the (plan, due date) period: inserts a generated run, or takes over a previously
     * failed one. Returns 0 when a generated/skipped run already exists (duplicate prevention; the
     * unique constraint decides even when two transactions race).
     */
    @Modifying
    @Query(value = "INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status, source) "
        + "VALUES (:id, :tenantId, :planId, :dueDate, 'generated', :source) "
        + "ON CONFLICT (plan_id, due_date) DO UPDATE SET status = 'generated', error = NULL, source = EXCLUDED.source "
        + "WHERE maintenance_plan_runs.status = 'failed'", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("planId") UUID planId,
              @Param("dueDate") LocalDate dueDate, @Param("source") String source);

    @Modifying
    @Query(value = "UPDATE maintenance_plan_runs SET work_order_id = :workOrderId "
        + "WHERE plan_id = :planId AND due_date = :dueDate AND tenant_id = :tenantId", nativeQuery = true)
    int attachWorkOrder(@Param("tenantId") UUID tenantId, @Param("planId") UUID planId,
                        @Param("dueDate") LocalDate dueDate, @Param("workOrderId") UUID workOrderId);

    /** Records a failed run; returns 1 only when the failure is new (so it is notified once). */
    @Modifying
    @Query(value = "INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status, source, error) "
        + "VALUES (:id, :tenantId, :planId, :dueDate, 'failed', :source, :error) "
        + "ON CONFLICT (plan_id, due_date) DO NOTHING", nativeQuery = true)
    int insertFailed(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("planId") UUID planId,
                     @Param("dueDate") LocalDate dueDate, @Param("source") String source, @Param("error") String error);

    @Modifying
    @Query(value = "UPDATE maintenance_plan_runs SET error = :error "
        + "WHERE plan_id = :planId AND due_date = :dueDate AND tenant_id = :tenantId AND status = 'failed'", nativeQuery = true)
    int updateFailedError(@Param("tenantId") UUID tenantId, @Param("planId") UUID planId,
                          @Param("dueDate") LocalDate dueDate, @Param("error") String error);

    /** Generated runs past their due date whose work order is still open and not yet notified. */
    @Query(value = "SELECT r.id FROM maintenance_plan_runs r JOIN work_orders w ON w.id = r.work_order_id "
        + "WHERE r.status = 'generated' AND r.overdue_notified_at IS NULL AND r.due_date < :today "
        + "AND w.deleted_at IS NULL AND w.status::text NOT IN ('completed','closed','cancelled') "
        + "ORDER BY r.due_date, r.id LIMIT :lim", nativeQuery = true)
    List<UUID> findOverdueUnnotified(@Param("today") LocalDate today, @Param("lim") int limit);

    /** Marks the run notified; 0 when another pass already did. */
    @Modifying
    @Query(value = "UPDATE maintenance_plan_runs SET overdue_notified_at = now() "
        + "WHERE id = :id AND overdue_notified_at IS NULL", nativeQuery = true)
    int markOverdueNotified(@Param("id") UUID id);
}
