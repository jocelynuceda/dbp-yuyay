package com.yuyay.consultation.entity;

import com.yuyay.health.entity.HealthEntryVersion;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "handoff_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_handoff_item", columnNames = {"handoff_id", "health_entry_version_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoffItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "handoff_id", nullable = false)
    private Handoff handoff;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "health_entry_version_id", nullable = false)
    private HealthEntryVersion healthEntryVersion;
}
