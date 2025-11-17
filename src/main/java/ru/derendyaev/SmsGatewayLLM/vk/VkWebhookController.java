package ru.derendyaev.SmsGatewayLLM.vk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;
import ru.derendyaev.SmsGatewayLLM.service.MessageDeduplicationService;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class VkWebhookController {

    private final GigaChatClient gigaChatClient;
    private final SmsService smsService; // не нужен, но оставим для единообразия
    private final PromptBuilder promptBuilder;
    private final UserService userService;
    private final MessageDeduplicationService deduplicationService;

    @Value("${app.values.vk.group-id}")
    private String groupId;

    @Value("${app.values.vk.access-token}")
    private String accessToken;

    private final VkClient vkClient; // создадим ниже

    private static final String LLM_PREFIX = "/llm";
    private static final String ADMIN_CONTACT = "https://t.me/dmitrii_derendyaev";

    @PostMapping("/vk")
    public ResponseEntity<String> handleVkCallback(@RequestBody Map<String, Object> body) {
        String type = (String) body.get("type");

        // === 1) Подтверждение сервера ===
        if ("confirmation".equals(type)) {
            return ResponseEntity.ok("c680dcf5");
        }

        // === 2) Пришло новое сообщение ===
        if ("message_new".equals(type)) {
            Map<String, Object> obj = (Map<String, Object>) body.get("object");
            Map<String, Object> message = (Map<String, Object>) obj.get("message");

            Integer userId = (Integer) message.get("from_id");
            String text = (String) message.get("text");
            String externalMessageId = message.get("id").toString();

            log.info("Сообщение из ВК: userId={}, text={}", userId, text);

            // --- Дедупликация ---
            if (deduplicationService.isDuplicate(text, String.valueOf(userId), externalMessageId)) {
                return ResponseEntity.ok("ok");
            }
            deduplicationService.registerMessage(text, String.valueOf(userId), externalMessageId);

            // --- Проверка префикса /llm ---
            if (text == null || !text.trim().startsWith(LLM_PREFIX)) {
                vkClient.sendMessage(userId, "Команда должна начинаться с /llm");
                return ResponseEntity.ok("ok");
            }

            // --- Проверяем регистрацию пользователя ---
            Optional<UserEntity> userOpt = userService.getByVkId(userId); // нужно добавить метод!
            if (userOpt.isEmpty()) {
                vkClient.sendMessage(userId,
                        "❌ Ваш аккаунт не зарегистрирован.\n" +
                                "Получите доступ у администратора: " + ADMIN_CONTACT);
                return ResponseEntity.ok("ok");
            }

            UserEntity user = userOpt.get();
            int balance = user.getTokens();

            if (balance <= 0) {
                vkClient.sendMessage(userId,
                        "⚠️ Недостаточно токенов.\nПополните баланс на сайте.");
                return ResponseEntity.ok("ok");
            }

            // --- Запрос в GigaChat ---
            GigaMessageRequest rq = new GigaMessageRequest(
                    "GigaChat",
                    false,
                    0,
                    promptBuilder.buildMessages(text),
                    1,
                    Math.min(balance, 512),
                    1.0
            );

            GigaMessageResponse resp;
            try {
                resp = gigaChatClient.gigaMessageGenerate(rq);
            } catch (Exception e) {
                vkClient.sendMessage(userId,
                        "❌ Ошибка LLM. Связь с админом: " + ADMIN_CONTACT);
                return ResponseEntity.ok("ok");
            }

            int used = resp.getUsage() != null ? resp.getUsage().getTotalTokens() : 1;
            user.setTokens(Math.max(balance - used, 0));
            userService.saveUser(user);

            vkClient.sendMessage(
                    userId,
                    resp.toString() + "\n\n" +
                            "💰 Потрачено токенов: " + used + "\n" +
                            "📊 Остаток токенов: " + user.getTokens()
            );

            return ResponseEntity.ok("ok");
        }

        return ResponseEntity.ok("ok");
    }
}
