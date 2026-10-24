package com.smms.reports;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.reports.ReportModels.*;
import com.smms.reports.ReportService.Request;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportServiceTest {

    private final ReportQueries queries = mock(ReportQueries.class);
    private final WorkOrderAccessPolicy policy = mock(WorkOrderAccessPolicy.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
    private final ReportService service = new ReportService(queries, policy, clock);
    private final UUID tenant = UUID.randomUUID();
    private final User admin = Fixtures.user(UserRole.system_admin, null, null, tenant);

    private Request req() {
        return new Request(new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5)), new ReportScope(tenant, null, null));
    }

    @Test
    void volumeTotalsGroupsAndDefaultsToStatus() {
        when(queries.volume(any(), any(), eq("status"))).thenReturn(List.of(new Group("new", "new", 2), new Group("closed", "closed", 5)));
        Volume v = service.volume(req(), null);
        assertThat(v.total()).isEqualTo(7);
        assertThat(v.groupBy()).isEqualTo("status");
    }

    @Test
    void unknownGroupByIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.volume(req(), "status; DROP TABLE x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.turnaround(req(), "controlRoom")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queries);
    }

    @Test
    void missingEscalationTableYieldsAvailableFalseNotAnError() {
        when(queries.escalations(any(), any())).thenReturn(Optional.empty());
        EscalationReport e = service.escalations(req());
        assertThat(e.available()).isFalse();
        assertThat(e.groups()).isEmpty();
    }

    @Test
    void scopeIsDerivedFromTheActorAndRangeIsValidatedFirst() {
        when(policy.resolveScope(admin)).thenReturn(WorkOrderScope.all());
        assertThat(service.resolve(admin, null, null, null, null).range().to()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThatThrownBy(() -> service.resolve(admin, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        User tech = Fixtures.user(UserRole.technician, null, null, tenant);
        when(policy.resolveScope(tech)).thenReturn(WorkOrderScope.of(null, tech.getId(), null));
        assertThatThrownBy(() -> service.resolve(tech, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
