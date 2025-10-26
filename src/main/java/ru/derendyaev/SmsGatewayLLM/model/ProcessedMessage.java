package ru.derendyaev.SmsGatewayLLM.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_messages", indexes = {
        @Index(name = "idx_message_hash", columnList = "messageHash", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String messageHash;

    @Column(nullable = false, length = 50)
    private String sourcePhone;

    @Column(length = 128)
    private String externalMessageId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false)
    private LocalDateTime receivedAt;
}