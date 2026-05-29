package com.smms.service;

import com.smms.entity.Tenant;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class TenantService {

    private final TenantRepository repo;

    @Transactional(readOnly = true)
    public List<Tenant> listAll() {
        return repo.findAll();
    }

    @Transactional(readOnly = true)
    public Tenant getById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    public Tenant create(String name, String nameAr, String slug) {
        if (StringUtils.hasText(slug) && repo.existsBySlug(slug))
            throw new IllegalArgumentException("Slug already in use: " + slug);
        if (repo.existsByName(name))
            throw new IllegalArgumentException("Tenant name already in use: " + name);
        return repo.save(Tenant.builder()
            .name(name).nameAr(nameAr)
            .slug(slug != null ? slug.toLowerCase().replaceAll("[^a-z0-9-]", "-") : null)
            .isActive(true).build());
    }

    public Tenant toggleActive(UUID id, boolean active) {
        Tenant t = getById(id);
        t.setActive(active);
        return repo.save(t);
    }
}
