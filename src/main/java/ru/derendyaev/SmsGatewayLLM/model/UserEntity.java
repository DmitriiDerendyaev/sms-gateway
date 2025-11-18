package ru.derendyaev.SmsGatewayLLM.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long telegramId;

    @Column(name = "vk_user_id")
    private Integer vkUserId;

    private String phoneNumber;

    private String username;

    @Builder.Default
    private Integer tokens = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
