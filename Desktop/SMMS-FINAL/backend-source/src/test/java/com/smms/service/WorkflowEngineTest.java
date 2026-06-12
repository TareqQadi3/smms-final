package com.smms.service;

import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.WorkflowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the SMMS workflow state machine.
 * Covers every legal transition, role enforcement, terminal states,
 * and the system_admin structural bypass.
 */
class WorkflowEngineTest {

    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine();
    }

    @Nested
    @DisplayName("Valid transitions per role")
    class ValidTransitions {

        @ParameterizedTest(name = "{0} → {1} by {2}")
        @CsvSource({
            // from, to, actor
            "NEW,               UNDER_REVIEW,      control_room",
            "NEW,               CANCELLED,         control_room",
            "NEW,               CANCELLED,         maintenance_manager",
            "NEW,               CANCELLED,         hospital_supervisor",
            "UNDER_REVIEW,      ASSIGNED,          control_room",
            "UNDER_REVIEW,      CANCELLED,         control_room",
            "UNDER_REVIEW,      CANCELLED,         maintenance_manager",
            "ASSIGNED,          IN_PROGRESS,       technician",
            "ASSIGNED,          CANCELLED,         control_room",
            "ASSIGNED,          CANCELLED,         maintenance_manager",
            "IN_PROGRESS,       COMPLETED,         technician",
            "IN_PROGRESS,       WAITING_MATERIALS, technician",
            "IN_PROGRESS,       CANCELLED,         control_room",
            "IN_PROGRESS,       CANCELLED,         maintenance_manager",
            "WAITING_MATERIALS, WAITING_APPROVAL,  warehouse",
            "WAITING_MATERIALS, IN_PROGRESS,       warehouse",
            "WAITING_APPROVAL,  IN_PROGRESS,       operating_company_manager",
            "WAITING_APPROVAL,  WAITING_MATERIALS, operating_company_manager",
            "COMPLETED,         CLOSED,            control_room",
            "COMPLETED,         CLOSED,            maintenance_manager",
            "COMPLETED,         IN_PROGRESS,       control_room",
        })
        void allowsLegalTransition(TicketStatus from, TicketStatus to, UserRole actor) {
            engine.validate(from, to, actor); // must not throw
            assertThat(engine.canTransition(from, to, actor)).isTrue();
        }
    }

    @Nested
    @DisplayName("Role enforcement")
    class RoleEnforcement {

        @Test
        void technicianCannotAssignTickets() {
            assertThatThrownBy(() ->
                engine.validate(TicketStatus.UNDER_REVIEW, TicketStatus.ASSIGNED, UserRole.technician))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("technician");
        }

        @Test
        void technicianCannotCloseTickets() {
            assertThatThrownBy(() ->
                engine.validate(TicketStatus.COMPLETED, TicketStatus.CLOSED, UserRole.technician))
                .isInstanceOf(WorkflowException.class);
        }

        @Test
        void residentCannotPerformAnyTransition() {
            assertThat(engine.canTransition(TicketStatus.NEW, TicketStatus.UNDER_REVIEW, UserRole.resident)).isFalse();
            assertThat(engine.canTransition(TicketStatus.IN_PROGRESS, TicketStatus.COMPLETED, UserRole.resident)).isFalse();
            assertThat(engine.canTransition(TicketStatus.COMPLETED, TicketStatus.CLOSED, UserRole.resident)).isFalse();
        }

        @Test
        void onlyWarehouseHandlesWaitingMaterials() {
            assertThat(engine.canTransition(TicketStatus.WAITING_MATERIALS, TicketStatus.WAITING_APPROVAL, UserRole.control_room)).isFalse();
            assertThat(engine.canTransition(TicketStatus.WAITING_MATERIALS, TicketStatus.IN_PROGRESS, UserRole.technician)).isFalse();
        }

        @Test
        void onlyOperatingCompanyManagerApproves() {
            assertThat(engine.canTransition(TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.maintenance_manager)).isFalse();
            assertThat(engine.canTransition(TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.warehouse)).isFalse();
        }
    }

    @Nested
    @DisplayName("Structurally invalid transitions")
    class InvalidTransitions {

        @Test
        void cannotSkipFromNewToInProgress() {
            assertThatThrownBy(() ->
                engine.validate(TicketStatus.NEW, TicketStatus.IN_PROGRESS, UserRole.control_room))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("not allowed");
        }

        @Test
        void cannotSkipFromNewToClosed() {
            assertThat(engine.canTransition(TicketStatus.NEW, TicketStatus.CLOSED, UserRole.control_room)).isFalse();
        }

        @ParameterizedTest(name = "no exits from terminal state {0}")
        @EnumSource(value = TicketStatus.class, names = {"CLOSED", "CANCELLED"})
        void terminalStatesHaveNoExits(TicketStatus terminal) {
            for (TicketStatus to : TicketStatus.values()) {
                assertThat(engine.canTransition(terminal, to, UserRole.control_room))
                    .as("%s → %s must be blocked", terminal, to)
                    .isFalse();
            }
            assertThatThrownBy(() ->
                engine.validate(terminal, TicketStatus.NEW, UserRole.control_room))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("No transitions allowed");
        }

        @Test
        void selfTransitionIsRejected() {
            assertThat(engine.canTransition(TicketStatus.IN_PROGRESS, TicketStatus.IN_PROGRESS, UserRole.technician)).isFalse();
        }
    }

    @Nested
    @DisplayName("system_admin bypass")
    class SystemAdminBypass {

        @Test
        void adminCanPerformAnyStructurallyValidTransition() {
            engine.validate(TicketStatus.NEW, TicketStatus.UNDER_REVIEW, UserRole.system_admin);
            engine.validate(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, UserRole.system_admin);
            engine.validate(TicketStatus.COMPLETED, TicketStatus.CLOSED, UserRole.system_admin);
        }

        @Test
        void adminStillBlockedFromStructurallyInvalidTransition() {
            assertThatThrownBy(() ->
                engine.validate(TicketStatus.NEW, TicketStatus.COMPLETED, UserRole.system_admin))
                .isInstanceOf(WorkflowException.class);
        }

        @Test
        void adminBlockedFromTerminalStates() {
            assertThatThrownBy(() ->
                engine.validate(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS, UserRole.system_admin))
                .isInstanceOf(WorkflowException.class);
        }
    }

    @Nested
    @DisplayName("resolveStage labels")
    class ResolveStage {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "NEW,               new",
            "UNDER_REVIEW,      under_review",
            "ASSIGNED,          assigned",
            "IN_PROGRESS,       in_progress",
            "WAITING_MATERIALS, waiting_materials",
            "WAITING_APPROVAL,  waiting_approval",
            "COMPLETED,         completed",
            "CLOSED,            closed",
            "CANCELLED,         cancelled",
        })
        void mapsEveryStatusToUiLabel(TicketStatus status, String expected) {
            assertThat(engine.resolveStage(status)).isEqualTo(expected);
        }
    }
}
