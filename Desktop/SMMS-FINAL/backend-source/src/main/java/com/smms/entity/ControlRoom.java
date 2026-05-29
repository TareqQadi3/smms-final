package com.smms.entity;

import com.smms.enums.ControlRoomType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "control_rooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ControlRoom extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "center_id", nullable = false)
    private Center center;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "control_room_type")
    private ControlRoomType type;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(length = 20)
    private String code;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}
