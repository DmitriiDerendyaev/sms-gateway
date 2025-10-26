package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.derendyaev.SmsGatewayLLM.model.ProcessedMessage;

import java.util.Optional;

@Repository
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {
    Optional<ProcessedMessage> findByMessageHash(String messageHash);

    boolean existsByMessageHash(String messageHash);
}
