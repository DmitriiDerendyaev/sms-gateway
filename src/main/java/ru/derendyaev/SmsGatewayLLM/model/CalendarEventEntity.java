package ru.derendyaev.SmsGatewayLLM.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "calendar_events", indexes = {
        @Index(name = "idx_calendar_events_user_id", columnList = "user_id"),
        @Index(name = "idx_calendar_events_google_event_id", columnList = "google_event_id"),
        @Index(name = "idx_calendar_events_start_time", columnList = "start_time")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "google_event_id", nullable = false, length = 255)
    private String googleEventId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(columnDefinition = "TEXT")
    private String attendees; // JSON-строка с массивом участников

    @Column(columnDefinition = "TEXT")
    private String reminders; // JSON-строка с настройками напоминаний

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

