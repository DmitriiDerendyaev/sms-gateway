package ru.derendyaev.SmsGatewayLLM.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.derendyaev.SmsGatewayLLM.model.ProcessedMessage;
import ru.derendyaev.SmsGatewayLLM.repository.ProcessedMessageRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeduplicationService {

    private final ProcessedMessageRepository repository;

    public boolean isDuplicate(String text, String phone, String externalId) {
        String hash = generateHash(text, phone, externalId);
        boolean exists = repository.existsByMessageHash(hash);
        log.debug("Хэш [{}] уже существует: {}", hash, exists);
        return exists;
    }

    public void registerMessage(String text, String phone, String externalId) {
        String hash = generateHash(text, phone, externalId);
        if (!repository.existsByMessageHash(hash)) {
            repository.save(ProcessedMessage.builder()
                    .messageHash(hash)
                    .sourcePhone(phone)
                    .externalMessageId(externalId)
                    .text(text)
                    .receivedAt(LocalDateTime.now())
                    .build());
            log.info("Сообщение зарегистрировано: {}", hash);
        } else {
            log.warn("Попытка повторной регистрации сообщения: {}", hash);
        }
    }

    private String generateHash(String text, String phone, String externalId) {
        try {
            String base = text.trim() + "|" + phone + "|" + (externalId != null ? externalId : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }
}
