package com.smms.service;

import com.smms.dto.response.AttachmentResponse;
import com.smms.entity.Attachment;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.AttachmentRepository;
import com.smms.repository.TicketHistoryRepository;
import com.smms.security.WorkOrderAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentServiceTest {

    private AttachmentRepository attachmentRepo;
    private WorkOrderAccessPolicy accessPolicy;
    private TicketHistoryRepository historyRepo;
    private AttachmentService service;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        attachmentRepo = mock(AttachmentRepository.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        historyRepo = mock(TicketHistoryRepository.class);
        service = new AttachmentService(attachmentRepo, accessPolicy, historyRepo);
        ReflectionTestUtils.setField(service, "uploadPath", tmp.toString());
        ReflectionTestUtils.setField(service, "allowedExtensions", "jpg,jpeg,png,pdf,doc,docx");
    }

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(UserRole.resident);
        return u;
    }

    @Test
    void listOutOfScopeIs404AndTouchesNoAttachments() {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));

        assertThatThrownBy(() -> service.list(id, actor)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(attachmentRepo);
    }

    @Test
    void listMapsToDtoWithoutFilePath() {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(new WorkOrder());
        Attachment a = Attachment.builder().id(UUID.randomUUID()).fileName("a.png").filePath("/srv/secret/a.png")
            .fileSize(12).mimeType("image/png").createdAt(Instant.now()).uploadedBy(actor).build();
        when(attachmentRepo.findByWorkOrderId(id)).thenReturn(List.of(a));

        List<AttachmentResponse> out = service.list(id, actor);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId()).isEqualTo(a.getId());
        assertThat(out.get(0).getFileName()).isEqualTo("a.png");
        assertThat(out.get(0).getFileSize()).isEqualTo(12);
        assertThat(out.get(0).getMimeType()).isEqualTo("image/png");
        assertThat(out.get(0).getUploadedById()).isEqualTo(actor.getId());
        assertThat(Arrays.stream(AttachmentResponse.class.getDeclaredFields()).map(f -> f.getName()))
            .doesNotContain("filePath", "path", "uploadedBy", "workOrder");
    }

    @Test
    void uploadWritesOneAuditHistoryRowPerRequest() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setStatus(com.smms.enums.TicketStatus.IN_PROGRESS);
        wo.setCurrentStage("in_progress");
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo);
        MockMultipartFile a = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
        MockMultipartFile b = new MockMultipartFile("file", "b.jpg", "image/jpeg", new byte[] {2});

        service.upload(id, List.of(a, b), actor);

        verify(attachmentRepo, times(2)).save(any(Attachment.class));
        org.mockito.ArgumentCaptor<com.smms.entity.TicketHistory> cap =
            org.mockito.ArgumentCaptor.forClass(com.smms.entity.TicketHistory.class);
        verify(historyRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getToStatus()).isEqualTo(com.smms.enums.TicketStatus.IN_PROGRESS);
        assertThat(cap.getValue().getFromStatus()).isEqualTo(com.smms.enums.TicketStatus.IN_PROGRESS);
        assertThat(cap.getValue().getActor()).isSameAs(actor);
        assertThat(cap.getValue().getNote()).contains("(2)").contains("a.png").contains("b.jpg");
    }

    @Test
    void uploadOutOfScopeWritesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[] {1, 2});

        assertThatThrownBy(() -> service.upload(id, List.of(f), actor)).isInstanceOf(ResourceNotFoundException.class);

        verify(attachmentRepo, never()).save(any());
        verifyNoInteractions(historyRepo);
        try (var files = Files.list(tmp)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void uploadInScopeSavesAndWrites() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(new WorkOrder());
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[] {1, 2});

        service.upload(id, List.of(f), actor);

        verify(attachmentRepo).save(any(Attachment.class));
        assertThat(Files.exists(tmp.resolve(id.toString()))).isTrue();
    }
}
