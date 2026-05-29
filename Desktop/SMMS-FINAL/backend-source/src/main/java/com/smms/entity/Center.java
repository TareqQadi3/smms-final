package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "centers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Center extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "op_company_logo")
    private String opCompanyLogo;

    private String address;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}
