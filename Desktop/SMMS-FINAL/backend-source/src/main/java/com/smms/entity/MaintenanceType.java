package com.smms.entity;

import com.smms.enums.ControlRoomType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "maintenance_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MaintenanceType extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    /** Which control-room type this maintenance type belongs to */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "control_room_type", nullable = false, columnDefinition = "control_room_type")
    private ControlRoomType controlRoomType;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}
