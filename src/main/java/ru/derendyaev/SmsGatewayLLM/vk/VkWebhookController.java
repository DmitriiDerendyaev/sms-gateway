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
// import ru.derendyaev.SmsGatewayLLM.model.UserEntity; // Временно не используется
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;
import ru.derendyaev.SmsGatewayLLM.service.MessageDeduplicationService;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // Хранение состояний пользователей VK (ожидание номера телефона)
    private final Map<Integer, String> vkUserStates = new ConcurrentHashMap<>();

    // Префикс /llm больше не обязателен - все сообщения обрабатываются
    // private static final String LLM_PREFIX = "/llm";
    private static final String ADMIN_CONTACT = "https://t.me/dmitrii_derendyaev";
    
    // Информация для всех сообщений
    private static final String FOOTER_INFO = "\n\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "👤 Администратор: " + ADMIN_CONTACT + "\n" +
            "⚠️ Внимание: Сервис скоро станет платным";
    
    // Приветственное сообщение для команды /start
    private static final String WELCOME_MESSAGE = "👋 Привет! Добро пожаловать в SmsGateway LLM!\n\n" +
            "🤖 Это бот для использования и взаимодействия с нейросетью.\n" +
            "📱 Пожалуйста, введите ваш номер телефона в формате:\n" +
            "   +7XXXXXXXXXX или 8XXXXXXXXXX";

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

            // --- Проверка на пустое сообщение ---
            if (text == null || text.trim().isEmpty()) {
                log.info("Получено пустое сообщение от пользователя {}", userId);
                vkClient.sendMessage(userId, "Пожалуйста, отправьте ваш вопрос или запрос" + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            String userMessage = text.trim();

            // --- Обработка состояния ожидания телефона ---
            if (vkUserStates.containsKey(userId)) {
                String state = vkUserStates.get(userId);
                if ("WAITING_PHONE".equals(state)) {
                    // Получаем username из сообщения (если доступно) или используем VK User ID
                    String username = null; // VK API не передаёт username напрямую в webhook
                    
                    // Регистрируем пользователя с телефоном
                    String result = userService.registerVkUserWithPhone(userId, username, userMessage);
                    vkClient.sendMessage(userId, result + FOOTER_INFO);
                    vkUserStates.remove(userId);
                    log.info("Пользователь {} зарегистрирован с телефоном", userId);
                    return ResponseEntity.ok("ok");
                }
            }

            // --- Обработка команды /start ---
            if ("/start".equalsIgnoreCase(userMessage) || "slash start".equalsIgnoreCase(userMessage)) {
                log.info("Получена команда /start от пользователя {}", userId);
                vkUserStates.put(userId, "WAITING_PHONE");
                vkClient.sendMessage(userId, WELCOME_MESSAGE + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            // Все остальные сообщения обрабатываются как запросы к LLM (префикс /llm не обязателен)
            log.info("Обработка запроса LLM от пользователя {}: {}", userId, userMessage);

            // === ВРЕМЕННО ОТКЛЮЧЕНО: Проверка пользователя и баланса ===
            // Открыт доступ для всех пользователей
            /*
            // --- Проверяем регистрацию пользователя ---
            Optional<UserEntity> userOpt = userService.getByVkId(userId);
            if (userOpt.isEmpty()) {
                log.warn("Пользователь {} не найден в базе данных", userId);
                vkClient.sendMessage(userId,
                        "❌ Ваш аккаунт не зарегистрирован.\n" +
                                "Получите доступ у администратора: " + ADMIN_CONTACT);
                return ResponseEntity.ok("ok");
            }

            UserEntity user = userOpt.get();
            int balance = user.getTokens();
            log.info("Пользователь {} найден, баланс токенов: {}", userId, balance);

            if (balance <= 0) {
                log.warn("У пользователя {} недостаточно токенов", userId);
                vkClient.sendMessage(userId,
                        "⚠️ Недостаточно токенов.\nПополните баланс на сайте.");
                return ResponseEntity.ok("ok");
            }
            */

            // --- Запрос в GigaChat ---
            log.info("Отправка запроса в GigaChat для пользователя {} (открытый доступ)", userId);
            GigaMessageRequest rq = new GigaMessageRequest(
                    "GigaChat",
                    false,
                    0,
                    promptBuilder.buildMessages(userMessage),
                    1,
                    512, // Фиксированное значение, так как проверка баланса отключена
                    1.0
            );

            GigaMessageResponse resp;
            try {
                resp = gigaChatClient.gigaMessageGenerate(rq);
                log.info("Получен ответ от GigaChat для пользователя {}", userId);
            } catch (Exception e) {
                log.error("Ошибка при запросе к GigaChat для пользователя {}: {}", userId, e.getMessage(), e);
                vkClient.sendMessage(userId,
                        "❌ Ошибка LLM. Связь с админом: " + ADMIN_CONTACT + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            // === ВРЕМЕННО ОТКЛЮЧЕНО: Списание токенов ===
            /*
            int used = resp.getUsage() != null ? resp.getUsage().getTotalTokens() : 1;
            user.setTokens(Math.max(balance - used, 0));
            userService.saveUser(user);
            log.info("Списано токенов: {}, остаток: {}", used, user.getTokens());
            */

            // Формируем ответ с информацией об администраторе и предупреждением
            String responseText = resp.toString() + FOOTER_INFO;
            
            log.info("Отправка ответа пользователю {}", userId);
            vkClient.sendMessage(userId, responseText);

            return ResponseEntity.ok("ok");
        }

        return ResponseEntity.ok("ok");
    }
}
