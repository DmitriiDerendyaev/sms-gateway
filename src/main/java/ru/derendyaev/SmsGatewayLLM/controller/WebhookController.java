package ru.derendyaev.SmsGatewayLLM.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.derendyaev.SmsGatewayLLM.controller.dto.SmsWebhookRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final GigaChatClient gigaChatClient;
    private final SmsService smsServiceClient;
    private final PromptBuilder promptBuilder;
    private final UserService userService;

    private static final String GIGA_CHAT_MODEL = "GigaChat";
    private static final String LLM_PREFIX = "/llm";

    private static final String ADMIN_CONTACT = "https://t.me/dmitrii_derendyaev";

    @PostMapping("/sms")
    public ResponseEntity<Void> handleSmsWebhook(@RequestBody SmsWebhookRequest request) {
        String userMessage = request.getPayload().getMessage();
        String phoneNumber = request.getPayload().getPhoneNumber();

        // ------------------- Проверка пользователя -------------------
        var userOpt = userService.getByPhoneNumber(phoneNumber);
        if (userOpt.isEmpty()) {
            smsServiceClient.sendSms(phoneNumber,
                    "❌ Ваш номер не зарегистрирован. Получите промокод у администратора: " + ADMIN_CONTACT +
                            "\nДля дополнительной информации: https://sms-gateway.derendyaev.ru/");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        var user = userOpt.get();

        // ------------------- Проверка токенов -------------------
        if (user.getTokens() <= 0) {
            smsServiceClient.sendSms(phoneNumber,
                    "⚠️ Недостаточно токенов. Пополните баланс на: https://sms-gateway.derendyaev.ru/\n" +
                            "Связаться с администратором: " + ADMIN_CONTACT);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).build();
        }

        // ------------------- Проверка префикса /llm -------------------
        if (userMessage == null || !userMessage.trim().startsWith(LLM_PREFIX)) {
            smsServiceClient.sendSms(phoneNumber, "❌ Сообщение должно начинаться с /llm");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }

        // ------------------- Генерация ответа LLM -------------------
        GigaMessageRequest gigaRequest = new GigaMessageRequest(
                GIGA_CHAT_MODEL,
                false,
                0,
                promptBuilder.buildMessages(userMessage),
                1,
                1024,
                1.0
        );

        GigaMessageResponse gigaResponse;
        try {
            gigaResponse = gigaChatClient.gigaMessageGenerate(gigaRequest);
        } catch (Exception e) {
            smsServiceClient.sendSms(phoneNumber,
                    "❌ Ошибка при обработке запроса LLM. Свяжитесь с администратором: " + ADMIN_CONTACT);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        String reply = gigaResponse.toString();

        // ------------------- Вычитаем токены -------------------
        int tokensUsed = gigaResponse.getUsage() != null ? gigaResponse.getUsage().getTotalTokens() : 1;
        int remainingTokens = Math.max(user.getTokens() - tokensUsed, 0);
        user.setTokens(remainingTokens);
        userService.saveUser(user);

        // ------------------- Отправка SMS -------------------
        smsServiceClient.sendSms(phoneNumber,
                reply + "\n\n💰 Потрачено токенов: " + tokensUsed +
                        "\n📊 Остаток токенов: " + remainingTokens +
                        "\nСвязаться с администратором: " + ADMIN_CONTACT);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
