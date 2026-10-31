package com.smms.workflow;

import com.smms.entity.TicketHistory;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.repository.TicketHistoryRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkOrderHistoryWriterTest {

    private final TicketHistoryRepository repo = mock(TicketHistoryRepository.class);
    private final WorkOrderHistoryWriter writer = new WorkOrderHistoryWriter(repo);

    private static User actor() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(UserRole.control_room);
        return u;
    }

    @Test
    void recordWritesTransitionRowWithCurrentStageAndSanitisedNote() {
        when(repo.save(any(TicketHistory.class))).thenAnswer(i -> i.getArgument(0));
        WorkOrder wo = WorkOrder.builder().currentStage("assigned").status(TicketStatus.ASSIGNED).build();
        User a = actor();

        TicketHistory h = writer.record(wo, TicketStatus.UNDER_REVIEW, TicketStatus.ASSIGNED, a,
            "<script>x</script>ok");

        assertThat(h.getWorkOrder()).isSameAs(wo);
        assertThat(h.getActor()).isSameAs(a);
        assertThat(h.getActorRole()).isEqualTo(UserRole.control_room);
        assertThat(h.getFromStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getToStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(h.getStage()).isEqualTo("assigned");
        assertThat(h.getNote()).doesNotContain("<script>");
        verify(repo).save(h);
    }

    @Test
    void recordAcceptsNullFromAndNullNote() {
        when(repo.save(any(TicketHistory.class))).thenAnswer(i -> i.getArgument(0));
        TicketHistory h = writer.record(WorkOrder.builder().build(), null, TicketStatus.NEW, actor(), null);
        assertThat(h.getFromStatus()).isNull();
        assertThat(h.getNote()).isNull();
    }

    @Test
    void noteKeepsStatusAndUsesNoteStage() {
        when(repo.save(any(TicketHistory.class))).thenAnswer(i -> i.getArgument(0));
        WorkOrder wo = WorkOrder.builder().status(TicketStatus.IN_PROGRESS).currentStage("in_progress").build();
        TicketHistory h = writer.note(wo, actor(), "checked the valve");
        assertThat(h.getFromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(h.getToStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(h.getStage()).isEqualTo("note");
        assertThat(h.getNote()).isEqualTo("checked the valve");
    }
}
