package ru.derendyaev.SmsGatewayLLM.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.derendyaev.SmsGatewayLLM.controller.dto.SmsWebhookRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

import java.util.Optional;


@Slf4j
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
        String rawPhoneNumber = request.getPayload().getPhoneNumber();

        // ------------------- Нормализация номера -------------------
        String phoneNumber = userService.normalizePhoneNumber(rawPhoneNumber);
        if (phoneNumber == null) {
            log.info("Некорректный номер: {}", rawPhoneNumber);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // ------------------- Проверка префикса /llm -------------------
        if (userMessage == null || !userMessage.trim().startsWith(LLM_PREFIX)) {
            log.info("Сообщение не начинается с /llm, phone={}", phoneNumber);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }

        // ------------------- Проверка пользователя -------------------
        Optional varUser = userService.getByPhoneNumber(phoneNumber);
        if (varUser.isEmpty()) {
            smsServiceClient.sendSms(rawPhoneNumber,
                    "❌ Ваш номер не зарегистрирован. Получите промокод у администратора: " + ADMIN_CONTACT +
                            "\nДля дополнительной информации: https://sms-gateway.derendyaev.ru/");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UserEntity user = (UserEntity) varUser.get();

        // ------------------- Проверка токенов -------------------
        if (user.getTokens() <= 0) {
            smsServiceClient.sendSms(rawPhoneNumber,
                    "⚠️ Недостаточно токенов. Пополните баланс на: https://sms-gateway.derendyaev.ru/\n" +
                            "Связаться с администратором: " + ADMIN_CONTACT);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).build();
        }

        // ------------------- Генерация ответа LLM -------------------
        GigaMessageResponse gigaResponse;
        try {
            GigaMessageRequest gigaRequest = new GigaMessageRequest(
                    GIGA_CHAT_MODEL,
                    false,
                    0,
                    promptBuilder.buildMessages(userMessage),
                    1,
                    1024,
                    1.0
            );

            // блокируем вызов, чтобы получить ответ один раз
            gigaResponse = gigaChatClient.gigaMessageGenerate(gigaRequest);

        } catch (Exception e) {
            log.error("Ошибка при генерации ответа для {}: {}", phoneNumber, e.getMessage());
            smsServiceClient.sendSms(rawPhoneNumber,
                    "❌ Ошибка при обработке запроса LLM. Свяжитесь с администратором: " + ADMIN_CONTACT);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // ------------------- Вычитаем токены -------------------
        int tokensUsed = gigaResponse.getUsage() != null ? gigaResponse.getUsage().getTotalTokens() : 1;
        int remainingTokens = Math.max(user.getTokens() - tokensUsed, 0);
        user.setTokens(remainingTokens);
        userService.saveUser(user);

        // ------------------- Отправка SMS -------------------
        smsServiceClient.sendSms(rawPhoneNumber,
                gigaResponse.toString() +
                        "\n\n💰 Потрачено токенов: " + tokensUsed +
                        "\n📊 Остаток токенов: " + remainingTokens +
                        "\nСвязаться с администратором: " + ADMIN_CONTACT);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}