package com.smms.repository;

import com.smms.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
    List<Attachment> findByWorkOrderId(UUID workOrderId);

    Optional<Attachment> findByIdAndWorkOrderId(UUID id, UUID workOrderId);
}
