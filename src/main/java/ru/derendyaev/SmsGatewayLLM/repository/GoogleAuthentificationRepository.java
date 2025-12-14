package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.derendyaev.SmsGatewayLLM.model.GoogleAuthentificationEntity;

import java.util.Optional;

public interface GoogleAuthentificationRepository extends JpaRepository<GoogleAuthentificationEntity, Long> {
    Optional<GoogleAuthentificationEntity> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}

