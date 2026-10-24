package com.smms.procurement.entity;

import com.smms.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "suppliers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Supplier extends BaseEntity {

    @Column(nullable = false, length = 40, updatable = false)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", length = 200)
    private String nameAr;

    @Column(length = 40)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "tax_number", length = 40)
    private String taxNumber;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
