package ru.derendyaev.SmsGatewayLLM.controller;


import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final GigaChatClient gigaChatClient;
    private final SmsService smsServiceClient;
    private final PromptBuilder promptBuilder;

    private static final String GIGA_CHAT_MODEL = "GigaChat";

    @PostMapping("/sms")
    public void handleSmsWebhook(@RequestBody SmsWebhookRequest request) {
        // 1. Получаем сообщение пользователя
        String userMessage = request.getPayload().getMessage();
        String phoneNumber = request.getPayload().getPhoneNumber();

        // 2. Собираем промпт
        // Генерация тем через GigaChat
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
    }
}