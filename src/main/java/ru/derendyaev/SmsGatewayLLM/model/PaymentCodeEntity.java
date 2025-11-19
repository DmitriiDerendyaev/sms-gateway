package ru.derendyaev.SmsGatewayLLM.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 4)
    private String code;

    @Column(name = "vk_user_id", nullable = false)
    private Integer vkUserId;

    @Builder.Default
    private Boolean isUsed = false;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime usedAt;
}

