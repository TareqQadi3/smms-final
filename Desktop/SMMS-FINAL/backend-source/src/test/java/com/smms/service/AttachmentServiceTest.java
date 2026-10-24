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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentServiceTest {

    private AttachmentRepository attachmentRepo;
    private WorkOrderAccessPolicy accessPolicy;
    private TicketHistoryRepository historyRepo;
    private com.smms.repository.UploadRequestRepository uploadRepo;
    private AttachmentService service;

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 3, 4};

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        attachmentRepo = mock(AttachmentRepository.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        historyRepo = mock(TicketHistoryRepository.class);
        uploadRepo = mock(com.smms.repository.UploadRequestRepository.class);
        service = new AttachmentService(attachmentRepo, accessPolicy, historyRepo, uploadRepo);
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
        MockMultipartFile a = new MockMultipartFile("file", "a.png", "image/png", PNG);
        MockMultipartFile b = new MockMultipartFile("file", "b.jpg", "image/jpeg", JPG);

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
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", PNG);

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
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", PNG);

        service.upload(id, List.of(f), actor);

        verify(attachmentRepo).save(any(Attachment.class));
        assertThat(Files.exists(tmp.resolve(id.toString()))).isTrue();
    }

    // ── P4: upload hardening ────────────────────────────────────

    private WorkOrder wo(UUID id, UUID tenant) {
        WorkOrder w = new WorkOrder();
        w.setId(id);
        w.setTenantId(tenant);
        return w;
    }

    private long fileCount() throws Exception {
        try (var s = Files.walk(tmp)) { return s.filter(Files::isRegularFile).count(); }
    }

    @Test
    void magicBytesMustMatchTheExtension() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        byte[] exe = "MZ\u0090\u0000 not an image".getBytes();
        for (String name : List.of("a.jpg", "a.jpeg", "a.png", "a.pdf", "a.doc", "a.docx")) {
            MockMultipartFile f = new MockMultipartFile("file", name, "image/png", exe);
            assertThatThrownBy(() -> service.upload(id, List.of(f), actor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        }
        verify(attachmentRepo, never()).save(any());
        assertThat(fileCount()).isZero();
    }

    @Test
    void realSignaturesOfEveryAllowedTypeAreAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        List<MockMultipartFile> files = List.of(
            new MockMultipartFile("file", "a.jpg", null, JPG),
            new MockMultipartFile("file", "a.jpeg", null, JPG),
            new MockMultipartFile("file", "a.png", null, PNG),
            new MockMultipartFile("file", "a.pdf", null, "%PDF-1.7 x".getBytes()),
            new MockMultipartFile("file", "a.doc", null, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1}),
            new MockMultipartFile("file", "a.docx", null, new byte[] {0x50, 0x4B, 0x03, 0x04, 1}));
        service.upload(id, List.copyOf(files), actor);
        verify(attachmentRepo, times(6)).save(any(Attachment.class));
        assertThat(fileCount()).isEqualTo(6);
    }

    @Test
    void storedMimeTypeIsServerDerivedNeverTheClientsClaim() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        service.upload(id, List.of(new MockMultipartFile("file", "a.png", "text/html", PNG)), actor);
        org.mockito.ArgumentCaptor<Attachment> cap = org.mockito.ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getMimeType()).isEqualTo("image/png");
    }

    @Test
    void oneBadFileInABatchWritesNoFileAtAll() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        MockMultipartFile ok = new MockMultipartFile("file", "ok.png", null, PNG);
        MockMultipartFile bad = new MockMultipartFile("file", "bad.png", null, "<html>".getBytes());
        assertThatThrownBy(() -> service.upload(id, List.of(ok, bad), actor)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fileCount()).isZero();
        verify(attachmentRepo, never()).save(any());
        verifyNoInteractions(historyRepo);
    }

    @Test
    void moreThanTenFilesPerRequestIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        List<MockMultipartFile> eleven = java.util.stream.IntStream.range(0, 11)
            .mapToObj(i -> new MockMultipartFile("file", "f" + i + ".png", null, PNG)).toList();
        assertThatThrownBy(() -> service.upload(id, List.copyOf(eleven), actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10");
        assertThat(fileCount()).isZero();
        // exactly ten is fine
        service.upload(id, List.copyOf(eleven.subList(0, 10)), actor);
        verify(attachmentRepo, times(10)).save(any(Attachment.class));
    }

    @Test
    void disallowedExtensionAndEmptyFileStayRejected() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        assertThatThrownBy(() -> service.upload(id,
            List.of(new MockMultipartFile("file", "a.exe", null, PNG)), actor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload(id,
            List.of(new MockMultipartFile("file", "a.png", null, new byte[0])), actor)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filesAreRemovedWhenSavingFailsMidway() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        when(attachmentRepo.save(any(Attachment.class)))
            .thenAnswer(i -> i.getArgument(0))
            .thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.upload(id, List.of(
            new MockMultipartFile("file", "a.png", null, PNG),
            new MockMultipartFile("file", "b.png", null, PNG)), actor)).hasMessage("db down");
        assertThat(fileCount()).isZero();
    }

    @Test
    void filesAreRemovedWhenTheTransactionRollsBackAndKeptOnCommit() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(id, List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor);
            assertThat(fileCount()).isEqualTo(1);
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.forEach(sy -> sy.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(fileCount()).isZero();

            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            service.upload(id, List.of(new MockMultipartFile("file", "b.png", null, PNG)), actor);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(
                sy -> sy.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            assertThat(fileCount()).isEqualTo(1);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── P4: idempotency ─────────────────────────────────────────

    private static final String KEY = "client-key-0001";

    @Test
    void invalidIdempotencyKeysAre400() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        MockMultipartFile f = new MockMultipartFile("file", "a.png", null, PNG);
        for (String bad : List.of("short", "x".repeat(65), "has space 1234", "semi;colon-123", "../../etc/pass", "")) {
            assertThatThrownBy(() -> service.upload(id, List.of(f), actor, bad))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(uploadRepo);
        verify(attachmentRepo, never()).save(any());
        // boundary lengths are valid
        when(uploadRepo.claim(any(), any(), any(), any(), any())).thenReturn(1);
        service.upload(id, List.of(f), actor, "12345678");
        service.upload(id, List.of(f), actor, "y".repeat(64));
    }

    @Test
    void firstRequestWithAKeyCreatesAndLinksTheRows() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        when(uploadRepo.claim(any(), eq(actor.getId()), eq(id), eq(KEY), any())).thenReturn(1);

        var outcome = service.upload(id, List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor, KEY);

        assertThat(outcome).isEqualTo(AttachmentService.UploadOutcome.CREATED);
        org.mockito.ArgumentCaptor<Attachment> cap = org.mockito.ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getUploadRequestId()).isNotNull();
        verify(historyRepo).save(any());
    }

    @Test
    void retryWithSameKeyAndSameFilesReplaysWithoutNewRowsOrHistory() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        org.mockito.ArgumentCaptor<String> fp = org.mockito.ArgumentCaptor.forClass(String.class);
        when(uploadRepo.claim(any(), any(), any(), any(), fp.capture())).thenReturn(1).thenReturn(0);
        List<MockMultipartFile> files = List.of(
            new MockMultipartFile("file", "a.png", null, PNG), new MockMultipartFile("file", "b.jpg", null, JPG));
        assertThat(service.upload(id, List.copyOf(files), actor, KEY)).isEqualTo(AttachmentService.UploadOutcome.CREATED);
        clearInvocations(attachmentRepo, historyRepo);
        long filesBefore = fileCount();
        when(uploadRepo.findByUserIdAndIdemKey(actor.getId(), KEY)).thenReturn(Optional.of(
            com.smms.entity.UploadRequest.builder().id(UUID.randomUUID()).userId(actor.getId())
                .workOrderId(id).idemKey(KEY).fingerprint(fp.getAllValues().get(0)).build()));

        // same files, reversed order: the fingerprint is order-independent
        var again = service.upload(id, List.of(files.get(1), files.get(0)), actor, KEY);

        assertThat(again).isEqualTo(AttachmentService.UploadOutcome.REPLAYED);
        verify(attachmentRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
        assertThat(fileCount()).isEqualTo(filesBefore);
    }

    @Test
    void sameKeyWithDifferentContentOrWorkOrderIsAConflict() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(any(UUID.class), eq(actor))).thenAnswer(i -> wo(i.getArgument(0), null));
        org.mockito.ArgumentCaptor<String> fp = org.mockito.ArgumentCaptor.forClass(String.class);
        when(uploadRepo.claim(any(), any(), any(), any(), fp.capture())).thenReturn(1).thenReturn(0).thenReturn(0);
        service.upload(id, List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor, KEY);
        String original = fp.getAllValues().get(0);
        when(uploadRepo.findByUserIdAndIdemKey(actor.getId(), KEY)).thenReturn(Optional.of(
            com.smms.entity.UploadRequest.builder().userId(actor.getId()).workOrderId(id).idemKey(KEY)
                .fingerprint(original).build()));
        clearInvocations(attachmentRepo, historyRepo);

        byte[] other = PNG.clone();
        other[other.length - 1] = 99;
        assertThat(service.upload(id, List.of(new MockMultipartFile("file", "a.png", null, other)), actor, KEY))
            .isEqualTo(AttachmentService.UploadOutcome.KEY_REUSED);
        // same content, other work order
        UUID otherWo = UUID.randomUUID();
        assertThat(service.upload(otherWo, List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor, KEY))
            .isEqualTo(AttachmentService.UploadOutcome.KEY_REUSED);
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void accessIsCheckedBeforeTheKeyIsLookedUp() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));
        assertThatThrownBy(() -> service.upload(id,
            List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor, KEY))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(uploadRepo);
    }

    @Test
    void withoutAKeyNothingIsClaimed() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        service.upload(id, List.of(new MockMultipartFile("file", "a.png", null, PNG)), actor);
        verifyNoInteractions(uploadRepo);
    }

    @Test
    void rejectedContentNeverClaimsTheKey() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo(id, null));
        assertThatThrownBy(() -> service.upload(id,
            List.of(new MockMultipartFile("file", "a.png", null, "<html>".getBytes())), actor, KEY))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(uploadRepo);
    }

    // ── P4: download ────────────────────────────────────────────

    private Attachment stored(UUID woId, UUID tenant, String fileName, Path file) {
        return Attachment.builder().id(UUID.randomUUID()).fileName(fileName).filePath(file.toString())
            .mimeType("text/html").tenantId(tenant).build();
    }

    private Path writeFor(UUID woId, String name, byte[] content) throws Exception {
        Path dir = tmp.resolve(woId.toString());
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.write(f, content);
        return f;
    }

    @Test
    void downloadReturnsServerDerivedTypeNotTheStoredClientValue() throws Exception {
        UUID woId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, tenant));
        Path f = writeFor(woId, "uuid_a.png", PNG);
        Attachment a = stored(woId, tenant, "a.png", f);
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));

        var d = service.download(woId, a.getId(), actor);

        assertThat(d.contentType()).isEqualTo("image/png"); // stored mime says text/html
        assertThat(d.size()).isEqualTo(PNG.length);
        assertThat(d.fileName()).isEqualTo("a.png");
        assertThat(Files.readAllBytes(d.path())).isEqualTo(PNG);
    }

    @Test
    void unknownExtensionFallsBackToOctetStream() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Path f = writeFor(woId, "x_blob", new byte[] {1});
        Attachment a = stored(woId, null, "blob.html", f);
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThat(service.download(woId, a.getId(), actor).contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void everyAllowedTypeMapsToItsOwnContentType() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        var expected = java.util.Map.of("a.jpg", "image/jpeg", "a.jpeg", "image/jpeg", "a.png", "image/png",
            "a.pdf", "application/pdf", "a.doc", "application/msword",
            "a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        for (var e : expected.entrySet()) {
            Path f = writeFor(woId, "u_" + e.getKey(), new byte[] {1});
            Attachment a = stored(woId, null, e.getKey(), f);
            when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
            assertThat(service.download(woId, a.getId(), actor).contentType()).isEqualTo(e.getValue());
        }
    }

    @Test
    void downloadOutOfScopeWorkOrderIs404AndNeverTouchesAttachments() {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found"));
        assertThatThrownBy(() -> service.download(woId, UUID.randomUUID(), actor)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(attachmentRepo);
    }

    @Test
    void attachmentOfAnotherWorkOrderIs404() {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        UUID attId = UUID.randomUUID();
        when(attachmentRepo.findByIdAndWorkOrderId(attId, woId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.download(woId, attId, actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossTenantAttachmentIs404() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, UUID.randomUUID()));
        Path f = writeFor(woId, "x_a.png", PNG);
        Attachment a = stored(woId, UUID.randomUUID(), "a.png", f); // another tenant than the work order
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.download(woId, a.getId(), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void tenantlessLegacyAttachmentOnATenantWorkOrderIsStillReadable() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, UUID.randomUUID()));
        Path f = writeFor(woId, "x_a.png", PNG);
        Attachment a = stored(woId, null, "a.png", f);
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThat(service.download(woId, a.getId(), actor).path()).isNotNull();
    }

    @Test
    void tamperedPathsOutsideTheWorkOrderDirectoryAre404() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Files.createDirectories(tmp.resolve(woId.toString()));
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        Path otherWo = writeFor(UUID.randomUUID(), "other.png", PNG);
        Path outside = Files.createTempFile("smms-outside", ".png");
        try {
            List<String> tampered = List.of(
                tmp.resolve(woId.toString()).resolve("../secret.txt").toString(),
                tmp.resolve(woId.toString()).resolve("../../../../../etc/passwd").toString(),
                secret.toString(),
                otherWo.toString(),                       // a real file of ANOTHER work order
                outside.toString(),
                tmp.resolve(woId.toString()).toString(),  // the directory itself
                "/etc/passwd");
            for (String p : tampered) {
                Attachment a = Attachment.builder().id(UUID.randomUUID()).fileName("a.png").filePath(p).build();
                when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
                assertThatThrownBy(() -> service.download(woId, a.getId(), actor))
                    .as(p).isInstanceOf(ResourceNotFoundException.class);
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void symlinkInsideTheDirectoryPointingOutsideIs404() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        Path dir = tmp.resolve(woId.toString());
        Files.createDirectories(dir);
        Path link = dir.resolve("link.png");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // platform without symlinks
        }
        Attachment a = stored(woId, null, "a.png", link);
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.download(woId, a.getId(), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingFileIs404() throws Exception {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Files.createDirectories(tmp.resolve(woId.toString()));
        Attachment a = stored(woId, null, "a.png", tmp.resolve(woId.toString()).resolve("gone.png"));
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.download(woId, a.getId(), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingUploadRootIs404NotA500() {
        ReflectionTestUtils.setField(service, "uploadPath", tmp.resolve("does-not-exist").toString());
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Attachment a = Attachment.builder().id(UUID.randomUUID()).fileName("a.png").filePath("/x/a.png").build();
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.download(woId, a.getId(), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void invalidPathCharactersAre404NotA500() {
        UUID woId = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(woId, actor)).thenReturn(wo(woId, null));
        Attachment a = Attachment.builder().id(UUID.randomUUID()).fileName("a.png").filePath("bad\u0000path").build();
        when(attachmentRepo.findByIdAndWorkOrderId(a.getId(), woId)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.download(woId, a.getId(), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── before/after photo phase (V25) ──

    @Test
    void phaseIsStoredNormalizedAndExposed() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setStatus(com.smms.enums.TicketStatus.IN_PROGRESS);
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo);
        MockMultipartFile a = new MockMultipartFile("file", "a.png", "image/png", PNG);

        service.upload(id, List.of(a), actor, null, " After ");

        org.mockito.ArgumentCaptor<Attachment> cap = org.mockito.ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getPhotoPhase()).isEqualTo("after");
        when(attachmentRepo.findByWorkOrderId(id)).thenReturn(List.of(cap.getValue()));
        assertThat(service.list(id, actor).get(0).getPhotoPhase()).isEqualTo("after");
        // no phase = general attachment
        clearInvocations(attachmentRepo);
        service.upload(id, List.of(a), actor);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getPhotoPhase()).isNull();
    }

    @Test
    void invalidPhaseIs400AndWritesNothing() {
        UUID id = UUID.randomUUID();
        User actor = user();
        when(accessPolicy.requireReadable(id, actor)).thenReturn(new WorkOrder());
        MockMultipartFile a = new MockMultipartFile("file", "a.png", "image/png", PNG);
        assertThatThrownBy(() -> service.upload(id, List.of(a), actor, null, "during"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void phaseIsPartOfTheIdempotencyFingerprint() throws Exception {
        UUID id = UUID.randomUUID();
        User actor = user();
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setStatus(com.smms.enums.TicketStatus.IN_PROGRESS);
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo);
        MockMultipartFile a = new MockMultipartFile("file", "a.png", "image/png", PNG);
        when(uploadRepo.claim(any(), any(), any(), any(), any())).thenReturn(1);
        org.mockito.ArgumentCaptor<String> fp = org.mockito.ArgumentCaptor.forClass(String.class);
        service.upload(id, List.of(a), actor, "key-0000001", "before");
        service.upload(id, List.of(a), actor, "key-0000002", "after");
        service.upload(id, List.of(a), actor, "key-0000003", null);
        verify(uploadRepo, times(3)).claim(any(), any(), any(), any(), fp.capture());
        assertThat(fp.getAllValues()).doesNotHaveDuplicates();
    }
}
