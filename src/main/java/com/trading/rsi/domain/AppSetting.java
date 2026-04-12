package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    @Id
    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "setting_value")
    private String value;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
