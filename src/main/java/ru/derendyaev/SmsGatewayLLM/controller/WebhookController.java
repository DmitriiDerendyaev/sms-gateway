package ru.derendyaev.SmsGatewayLLM.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.derendyaev.SmsGatewayLLM.controller.dto.SmsWebhookRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

import java.util.Set;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final GigaChatClient gigaChatClient;
    private final SmsService smsServiceClient;
    private final PromptBuilder promptBuilder;

    private static final String GIGA_CHAT_MODEL = "GigaChat";
    private static final String LLM_PREFIX = "/llm";

    // Список доверенных телефонов
    private static final Set<String> TRUSTED_PHONES = Set.of(
            "+79199192843", // Ева
            "+79225164775", // Саша
            "+79128720196", // Саша - 2
            "+79226925766", // Ольга
            "+79226851202", // Сергей
            "+79023912727", // Ярослав
            "+79199162038", // Наталья
            "+79199162037", // Юрий
            "+79120075789", // Даниил
            "+79295950512"  // Роман
    );

    @PostMapping("/sms")
    public ResponseEntity<Void> handleSmsWebhook(@RequestBody SmsWebhookRequest request) {
        String userMessage = request.getPayload().getMessage();
        String phoneNumber = request.getPayload().getPhoneNumber();

        // Проверка доверенного номера
        if (!TRUSTED_PHONES.contains(phoneNumber)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build(); // 403
        }

        // Проверка префикса /llm
        if (userMessage == null || !userMessage.trim().startsWith(LLM_PREFIX)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build(); // 422
        }

        // 2. Собираем промпт
        GigaMessageRequest gigaRequest = new GigaMessageRequest(
                GIGA_CHAT_MODEL,
                false,
                0,
                promptBuilder.buildMessages(userMessage),
                1,
                1024,
                1.0
        );

        // 3. Отправляем в LLM
        GigaMessageResponse gigaResponse = gigaChatClient.gigaMessageGenerate(gigaRequest);

        // 4. Берём текст ответа
        String reply = gigaResponse.toString();

        // 5. Отправляем обратно через SMS-клиент
        smsServiceClient.sendSms(phoneNumber, reply);

        return ResponseEntity.status(HttpStatus.CREATED).build(); // 201
    }
}
