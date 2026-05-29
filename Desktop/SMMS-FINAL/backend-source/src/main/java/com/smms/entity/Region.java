package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "regions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Region extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}
