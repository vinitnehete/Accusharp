package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A raw punch written straight into {@code device_logs} by the eSSL biometric
 * device. This application only ever reads it - there is no ingestion endpoint.
 */
@Entity
@Table(name = "device_logs", indexes = {
        @Index(name = "idx_device_logs_user_date", columnList = "user_id,log_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceLog {

    @Id
    @Column(name = "device_log_id")
    private Long deviceLogId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "log_date", nullable = false)
    private LocalDateTime logDate;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;
}
