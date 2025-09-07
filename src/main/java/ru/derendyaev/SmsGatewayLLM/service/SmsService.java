package ru.derendyaev.SmsGatewayLLM.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.derendyaev.SmsGatewayLLM.smsgateway.SmsGatewayClient;
import ru.derendyaev.SmsGatewayLLM.smsgateway.dto.MessageResponse;
import ru.derendyaev.SmsGatewayLLM.smsgateway.dto.SendMessageRequest;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final SmsGatewayClient smsGatewayClient;

    /**
     * Отправка SMS с полностью заполненным запросом
     */
    public void sendSms(String phoneNumber, String text) {
        SendMessageRequest sms = SendMessageRequest.builder()
                .phoneNumbers(Collections.singletonList(phoneNumber)) // из webhook
                .isEncrypted(false) // без шифрования
                .priority(100) // высокий приоритет
                .simNumber(1) // используем первую SIM
                .ttl(3600) // жить 1 час
                .withDeliveryReport(true) // запросить отчет о доставке
                .textMessage(new SendMessageRequest.TextMessage()) // создаём текстовое сообщение
                .build();

        sms.textMessage.text = text; // проставляем сам текст

        try {
            MessageResponse response = smsGatewayClient.sendMessage(sms);
            log.info("SMS успешно отправлено: id={}, phone={}, text={}, statusCode=202",
                    response.getId(), phoneNumber, text);
        } catch (Exception e) {
            log.error("Ошибка при отправке SMS пользователю {}: {}", phoneNumber, e.getMessage(), e);
            throw e;
        }
    }
}
