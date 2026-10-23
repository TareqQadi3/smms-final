package com.smms.service;

import com.smms.entity.*;
import com.smms.repository.*;
import com.smms.dto.response.AttachmentResponse;
import com.smms.security.WorkOrderAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachmentRepo;
    private final WorkOrderAccessPolicy accessPolicy;

    @Value("${app.upload.path:/app/uploads}")
    private String uploadPath;

    @Value("${app.upload.allowed-extensions:jpg,jpeg,png,pdf,doc,docx}")
    private String allowedExtensions;

    public void upload(UUID workOrderId, List<MultipartFile> files, User actor) {
        // Same visibility rule as the ticket itself (404 when out of scope), checked before any file I/O
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);

        files.forEach(file -> {
            validateFile(file);
            String filename = UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
            Path dest = Path.of(uploadPath, workOrderId.toString(), filename);
            try {
                Files.createDirectories(dest.getParent());
                Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("File upload failed: " + filename, e);
            }
            attachmentRepo.save(Attachment.builder()
                .workOrder(wo)
                .uploadedBy(actor)
                .fileName(file.getOriginalFilename())
                .filePath(dest.toString())
                .fileSize((int) file.getSize())
                .mimeType(file.getContentType())
                .build());
        });
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
                .build())
            .toList();
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        String name = file.getOriginalFilename();
        if (name == null) throw new IllegalArgumentException("Missing filename");
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        if (!Arrays.asList(allowedExtensions.split(",")).contains(ext)) {
            throw new IllegalArgumentException("File type not allowed: " + ext);
        }
    }

    private String sanitize(String name) {
        return name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
