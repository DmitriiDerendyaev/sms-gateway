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
     * Синхронная отправка SMS с заполнением всех полей
     */
    public void sendSms(String phoneNumber, String text) {
        SendMessageRequest sms = SendMessageRequest.builder()
                .phoneNumbers(Collections.singletonList(phoneNumber))
                .isEncrypted(false)          // без шифрования
                .priority(100)               // высокий приоритет
                .simNumber(1)                // используем первую SIM
                .ttl(3600)                   // время жизни сообщения 1 час
                .withDeliveryReport(true)    // запрос отчета о доставке
                .textMessage(new SendMessageRequest.TextMessage())
                .build();

        sms.textMessage.text = text;      // проставляем текст

        try {
            // синхронный вызов шлюза
            MessageResponse response = smsGatewayClient.sendMessage(sms);
            log.info("SMS успешно отправлено: id={}, phone={}, text={}, statusCode=202",
                    response.getId(), phoneNumber, text);
        } catch (Exception e) {
            log.error("Ошибка при отправке SMS пользователю {}: {}", phoneNumber, e.getMessage(), e);
            // не кидаем исключение наружу, чтобы не дублировалось
        }
    }
}
