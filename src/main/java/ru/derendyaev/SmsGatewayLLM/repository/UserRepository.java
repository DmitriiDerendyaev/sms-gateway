package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByTelegramId(Long telegramId);
    Optional<UserEntity> findByPhoneNumber(String phoneNumber);
    Optional<UserEntity> findByVkUserId(Integer vkUserId);
}
