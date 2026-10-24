package com.smms.service;

import com.smms.dto.response.AttachmentResponse;
import com.smms.entity.*;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.*;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.InputSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    public static final int MAX_FILES_PER_REQUEST = 10;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /** Server-side content types by extension: the client-supplied type is never stored or served. */
    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg",
        "png", "image/png",
        "pdf", "application/pdf",
        "doc", "application/msword",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Leading bytes each extension must start with. */
    private static final Map<String, byte[]> MAGIC_BY_EXTENSION = Map.of(
        "jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
        "jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
        "png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47},
        "pdf", new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D},                       // %PDF-
        "doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0},
        "docx", new byte[]{0x50, 0x4B, 0x03, 0x04});                            // PK\3\4

    public enum UploadOutcome { CREATED, REPLAYED, KEY_REUSED }

    /** A download that passed every check; the caller streams {@code path}. */
    public record Download(Path path, String fileName, String contentType, long size) {}

    private final AttachmentRepository attachmentRepo;
    private final WorkOrderAccessPolicy accessPolicy;
    private final TicketHistoryRepository historyRepo;
    private final UploadRequestRepository uploadRequestRepo;

    @Value("${app.upload.path:/app/uploads}")
    private String uploadPath;

    @Value("${app.upload.allowed-extensions:jpg,jpeg,png,pdf,doc,docx}")
    private String allowedExtensions;

    public UploadOutcome upload(UUID workOrderId, List<MultipartFile> files, User actor) {
        return upload(workOrderId, files, actor, null);
    }

    /**
     * Stores the files. With an {@code idempotencyKey} a retry of the same request (same files) returns
     * REPLAYED and changes nothing; the same key with other content is KEY_REUSED. Access is checked
     * first so the key cannot be used to probe a work order the actor may not see.
     */
    public UploadOutcome upload(UUID workOrderId, List<MultipartFile> files, User actor, String idempotencyKey) {
        return upload(workOrderId, files, actor, idempotencyKey, null);
    }

    /** Optional {@code phase} ("before" or "after") marks the files as before/after photos; part of the fingerprint. */
    public UploadOutcome upload(UUID workOrderId, List<MultipartFile> files, User actor, String idempotencyKey,
                                String phase) {
        // Same visibility rule as the ticket itself (404 when out of scope), checked before any file I/O
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);
        final String photoPhase = normalizePhase(phase);

        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException("At most " + MAX_FILES_PER_REQUEST + " files per request");
        }
        // Validate every file before anything is written
        files.forEach(this::validateFile);

        UUID requestId = null;
        if (idempotencyKey != null && !files.isEmpty()) {
            String fingerprint = fingerprint(workOrderId, files, photoPhase);
            requestId = UUID.randomUUID();
            if (uploadRequestRepo.claim(requestId, actor.getId(), workOrderId, idempotencyKey, fingerprint) == 0) {
                UploadRequest existing = uploadRequestRepo.findByUserIdAndIdemKey(actor.getId(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency record vanished"));
                boolean same = existing.getWorkOrderId().equals(workOrderId)
                    && existing.getFingerprint().equals(fingerprint);
                if (!same) log.warn("Idempotency-Key reused with different content (userId={}, workOrderId={})",
                    actor.getId(), workOrderId);
                return same ? UploadOutcome.REPLAYED : UploadOutcome.KEY_REUSED;
            }
        }

        List<Path> written = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                String filename = UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
                Path dest = Path.of(uploadPath, workOrderId.toString(), filename);
                try {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = file.getInputStream()) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("File upload failed: " + filename, e);
                }
                written.add(dest);
                attachmentRepo.save(Attachment.builder()
                    .workOrder(wo)
                    .uploadedBy(actor)
                    .fileName(file.getOriginalFilename())
                    .filePath(dest.toString())
                    .fileSize((int) file.getSize())
                    .mimeType(MIME_BY_EXTENSION.get(extensionOf(file.getOriginalFilename())))
                    .uploadRequestId(requestId)
                    .photoPhase(photoPhase)
                    .build());
                names.add(sanitize(file.getOriginalFilename()));
            }
            if (names.isEmpty()) return UploadOutcome.CREATED;
            // Audit: one history row per upload request (status unchanged), same transaction as the rows
            historyRepo.save(TicketHistory.builder()
                .workOrder(wo)
                .actor(actor)
                .actorRole(actor.getRole())
                .fromStatus(wo.getStatus())
                .toStatus(wo.getStatus())
                .stage(wo.getCurrentStage())
                .note(InputSanitizer.clean("Attachments added (" + names.size() + "): " + String.join(", ", names), 1000))
                .build());
        } catch (RuntimeException e) {
            deleteQuietly(written);
            throw e;
        }
        removeFilesIfTransactionRollsBack(written);
        return UploadOutcome.CREATED;
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(UUID workOrderId, User actor) {
        accessPolicy.requireReadable(workOrderId, actor);
        // DTO only: never expose the entity (server file path, lazy user graph)
        return attachmentRepo.findByWorkOrderId(workOrderId).stream()
            .map(a -> AttachmentResponse.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .fileSize(a.getFileSize())
                .mimeType(a.getMimeType())
                .createdAt(a.getCreatedAt())
                .uploadedById(a.getUploadedBy() == null ? null : a.getUploadedBy().getId())
                .photoPhase(a.getPhotoPhase())
                .build())
            .toList();
    }

    /**
     * Resolves a download. Every failure is the same 404 as a missing id: out of scope, wrong work
     * order, tenant mismatch, path outside the work order's upload directory, missing file.
     */
    @Transactional(readOnly = true)
    public Download download(UUID workOrderId, UUID attachmentId, User actor) {
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);
        Attachment att = attachmentRepo.findByIdAndWorkOrderId(attachmentId, workOrderId)
            .orElseThrow(AttachmentService::notFound);

        UUID woTenant = wo.getTenantId();
        UUID attTenant = att.getTenantId();
        if (woTenant != null && attTenant != null && !woTenant.equals(attTenant)) {
            log.warn("SECURITY: attachment download refused, tenant mismatch (attachmentId={}, userId={})",
                att.getId(), actor.getId());
            throw notFound();
        }

        final Path real;
        try {
            Path base = Path.of(uploadPath).toRealPath();
            real = Path.of(att.getFilePath()).toRealPath();
            if (!real.startsWith(base.resolve(workOrderId.toString()))) {
                log.warn("SECURITY: attachment download refused, stored path is outside the work order directory"
                    + " (attachmentId={}, userId={})", att.getId(), actor.getId());
                throw notFound();
            }
            if (!Files.isRegularFile(real)) throw notFound();
            String name = att.getFileName() == null || att.getFileName().isBlank() ? "file" : att.getFileName();
            return new Download(real, name,
                MIME_BY_EXTENSION.getOrDefault(extensionOf(name), "application/octet-stream"), Files.size(real));
        } catch (IOException | InvalidPathException e) {
            log.warn("Attachment file unavailable (attachmentId={}): {}", att.getId(), e.getClass().getSimpleName());
            throw notFound();
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Attachment not found");
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        String name = file.getOriginalFilename();
        if (name == null) throw new IllegalArgumentException("Missing filename");
        String ext = extensionOf(name);
        if (!Arrays.asList(allowedExtensions.split(",")).contains(ext)) {
            throw new IllegalArgumentException("File type not allowed: " + ext);
        }
        byte[] magic = MAGIC_BY_EXTENSION.get(ext);
        if (magic != null && !startsWith(file, magic)) {
            throw new IllegalArgumentException("File content does not match its type: " + ext);
        }
    }

    private static boolean startsWith(MultipartFile file, byte[] magic) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(magic.length);
            return Arrays.equals(head, magic);
        } catch (IOException e) {
            throw new IllegalArgumentException("File could not be read");
        }
    }

    private static String extensionOf(String name) {
        return name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** sha-256 over the work order id and the sorted (name, size, sha-256 of content) of every file. */
    private static String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return null;
        String p = phase.trim().toLowerCase(Locale.ROOT);
        if (!p.equals("before") && !p.equals("after")) {
            throw new IllegalArgumentException("phase must be 'before' or 'after'");
        }
        return p;
    }

    private static String fingerprint(UUID workOrderId, List<MultipartFile> files, String phase) {
        List<String> parts = new ArrayList<>();
        for (MultipartFile f : files) {
            parts.add(f.getOriginalFilename() + "|" + f.getSize() + "|" + sha256(f));
        }
        Collections.sort(parts);
        MessageDigest md = digest();
        md.update(workOrderId.toString().getBytes(StandardCharsets.UTF_8));
        // a phase changes the meaning of the request; no phase keeps the pre-V25 fingerprint
        if (phase != null) md.update(("\nphase=" + phase).getBytes(StandardCharsets.UTF_8));
        for (String p : parts) md.update(("\n" + p).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256(MultipartFile f) {
        MessageDigest md = digest();
        try (InputStream in = f.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } catch (IOException e) {
            throw new IllegalArgumentException("File could not be read");
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Files are written before the commit; when the transaction rolls back they would be orphans. */
    private void removeFilesIfTransactionRollsBack(List<Path> written) {
        if (written.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) deleteQuietly(written);
            }
        });
    }

    private static void deleteQuietly(List<Path> paths) {
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException | RuntimeException ignored) { /* best effort */ }
        }
    }

    private String sanitize(String name) {
        return name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
