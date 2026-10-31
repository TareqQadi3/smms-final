package com.smms.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(UUID id, String name, String nameAr, String slug, boolean active, Instant createdAt) {}
