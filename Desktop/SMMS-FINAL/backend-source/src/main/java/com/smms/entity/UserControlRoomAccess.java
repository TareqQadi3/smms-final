package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "user_control_room_access",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id","control_room_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserControlRoomAccess {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_room_id", nullable = false)
    private ControlRoom controlRoom;

    @CreationTimestamp
    @Column(name = "granted_at", updatable = false)
    private Instant grantedAt;
}
