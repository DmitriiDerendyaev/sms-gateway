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
import ru.derendyaev.SmsGatewayLLM.service.PaymentService;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;

import java.util.Map;
import java.util.Optional;
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
    private final PaymentService paymentService;

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
            "━━━━━━━━━━━━━━━━━━━━━━\n" +
            "👤 Администратор: " + ADMIN_CONTACT + "\n" +
            "⚠️ Внимание: Сервис скоро станет платным";
    
    // Приветственное сообщение для команды /start
    private static final String WELCOME_MESSAGE = "👋 Привет! Добро пожаловать в SmsGateway LLM!\n\n" +
            "🤖 Это бот для использования и взаимодействия с нейросетью.\n\n" +
            "💰 При регистрации вы получите 5000 токенов в подарок!\n\n" +
            "🎟️ Вы также можете активировать промокод командой:\n" +
            "   /promo <ваш_промокод>\n\n" +
            "💳 Для покупки токенов используйте команду:\n" +
            "   /buy\n" +
            "   1 рубль = 100 токенов\n\n" +
            "📱 Пожалуйста, введите ваш номер телефона в формате:\n" +
            "   +7XXXXXXXXXX или 8XXXXXXXXXX";
    
    private static final String PAYMENT_PHONE = "892225070232";

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
            Object messageIdObj = message.get("id");
            String externalMessageId = messageIdObj != null ? messageIdObj.toString() : null;

            log.info("Сообщение из ВК: userId={}, text='{}', messageId={}", userId, text, externalMessageId);
            log.debug("Полное сообщение: {}", message);

            // --- Проверка на пустое сообщение ---
            if (text == null || text.trim().isEmpty()) {
                log.info("Получено пустое сообщение от пользователя {}", userId);
                vkClient.sendMessage(userId, "Пожалуйста, отправьте ваш вопрос или запрос" + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            String userMessage = text.trim();
            log.debug("Обработанное сообщение: '{}' (длина: {})", userMessage, userMessage.length());

            // --- Обработка команды /start (ПЕРЕД дедупликацией, чтобы команда всегда обрабатывалась) ---
            if ("/start".equalsIgnoreCase(userMessage) || "Начать".equalsIgnoreCase(userMessage)) {
                log.info("Получена команда /start от пользователя {}", userId);
                vkUserStates.put(userId, "WAITING_PHONE");
                vkClient.sendMessage(userId, WELCOME_MESSAGE + FOOTER_INFO);
                // Не регистрируем команду в дедупликации, чтобы её можно было использовать повторно
                return ResponseEntity.ok("ok");
            }

            // --- Обработка команды /promo (активация промокода) ---
            if (userMessage.startsWith("/promo")) {
                log.info("Получена команда /promo от пользователя {}", userId);
                String[] parts = userMessage.split(" ");
                if (parts.length < 2) {
                    vkClient.sendMessage(userId, "❌ Введите промокод в формате: /promo ABCD1234" + FOOTER_INFO);
                    return ResponseEntity.ok("ok");
                }

                String promoCode = parts[1].trim();
                
                // Проверяем, зарегистрирован ли пользователь
                Optional<UserEntity> userOpt = userService.getByVkId(userId);
                if (userOpt.isEmpty()) {
                    vkClient.sendMessage(userId,
                            "❌ Вы не зарегистрированы.\n\n" +
                            "Для регистрации отправьте команду /start и следуйте инструкциям." + FOOTER_INFO);
                    return ResponseEntity.ok("ok");
                }

                // Активируем промокод
                String result = userService.activatePromoForVkUser(userId, promoCode);
                vkClient.sendMessage(userId, result + FOOTER_INFO);
                // Не регистрируем в дедупликации, чтобы можно было повторить с другим промокодом
                return ResponseEntity.ok("ok");
            }

            // --- Обработка команды /buy (покупка токенов) ---
            if ("/buy".equalsIgnoreCase(userMessage)) {
                log.info("Получена команда /buy от пользователя {}", userId);
                
                // Проверяем, зарегистрирован ли пользователь
                Optional<UserEntity> userOpt = userService.getByVkId(userId);
                if (userOpt.isEmpty()) {
                    vkClient.sendMessage(userId,
                            "❌ Вы не зарегистрированы.\n\n" +
                            "Для регистрации отправьте команду /start и следуйте инструкциям." + FOOTER_INFO);
                    return ResponseEntity.ok("ok");
                }

                // Генерируем платежный код
                String paymentCode = paymentService.generatePaymentCode(userId);
                
                String buyMessage = "💳 Покупка токенов\n\n" +
                        "💰 Курс: 1 рубль = 100 токенов\n\n" +
                        "📱 Для оплаты выполните СБП перевод на номер:\n" +
                        "   " + PAYMENT_PHONE + "\n\n" +
                        "🔑 В комментарии к переводу укажите код:\n" +
                        "   «" + paymentCode + "»\n\n" +
                        "✅ После оплаты токены будут автоматически начислены на ваш счет.\n\n" +
                        "⏱️ Код действителен в течение 24 часов.";
                
                vkClient.sendMessage(userId, buyMessage + FOOTER_INFO);
                // Не регистрируем в дедупликации, чтобы можно было повторить покупку
                return ResponseEntity.ok("ok");
            }

            // --- Обработка состояния ожидания телефона (тоже ПЕРЕД дедупликацией) ---
            if (vkUserStates.containsKey(userId)) {
                String state = vkUserStates.get(userId);
                if ("WAITING_PHONE".equals(state)) {
                    log.info("Пользователь {} в состоянии WAITING_PHONE, обрабатываем номер телефона", userId);
                    // Получаем username из сообщения (если доступно) или используем VK User ID
                    String username = null; // VK API не передаёт username напрямую в webhook
                    
                    // Регистрируем пользователя с телефоном
                    String result = userService.registerVkUserWithPhone(userId, username, userMessage);
                    vkClient.sendMessage(userId, result + FOOTER_INFO);
                    vkUserStates.remove(userId);
                    log.info("Пользователь {} зарегистрирован с телефоном", userId);
                    // Не регистрируем в дедупликации, так как это одноразовое действие
                    return ResponseEntity.ok("ok");
                }
            }

            // --- Дедупликация (для обычных сообщений) ---
            if (deduplicationService.isDuplicate(text, String.valueOf(userId), externalMessageId)) {
                log.debug("Сообщение от пользователя {} является дубликатом, пропускаем", userId);
                return ResponseEntity.ok("ok");
            }
            deduplicationService.registerMessage(text, String.valueOf(userId), externalMessageId);

            // Все остальные сообщения обрабатываются как запросы к LLM (префикс /llm не обязателен)
            log.info("Обработка запроса LLM от пользователя {}: {}", userId, userMessage);

            // --- Проверяем регистрацию пользователя по VK ID ---
            Optional<UserEntity> userOpt = userService.getByVkId(userId);
            if (userOpt.isEmpty()) {
                log.warn("Пользователь {} не найден в базе данных", userId);
                vkClient.sendMessage(userId,
                        "❌ Ваш аккаунт не зарегистрирован.\n\n" +
                                "Для регистрации отправьте команду /start и следуйте инструкциям.\n\n" +
                                "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            UserEntity user = userOpt.get();
            
            // --- Проверяем, что у пользователя есть привязанный номер телефона ---
            if (user.getPhoneNumber() == null || user.getPhoneNumber().trim().isEmpty()) {
                log.warn("У пользователя {} нет привязанного номера телефона", userId);
                vkClient.sendMessage(userId,
                        "❌ У вас нет привязанного номера телефона.\n\n" +
                                "Для регистрации отправьте команду /start и введите ваш номер телефона.\n\n" +
                                "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            int balance = user.getTokens();
            log.info("Пользователь {} найден, номер телефона: {}, баланс токенов: {}", 
                    userId, user.getPhoneNumber(), balance);

            // --- Проверяем баланс токенов ---
            if (balance <= 0) {
                log.warn("У пользователя {} недостаточно токенов (баланс: {})", userId, balance);
                vkClient.sendMessage(userId,
                        "⚠️ Недостаточно токенов.\n\n" +
                                "Ваш текущий баланс: " + balance + " токенов.\n" +
                                "Пополните баланс для продолжения работы.\n\n" +
                                "Свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            // --- Запрос в GigaChat ---
            // Используем баланс токенов для max_tokens, но не более доступного баланса
            // Оставляем небольшой запас для обработки ответа
            int maxTokens = Math.min(balance, 512);
            log.info("Отправка запроса в GigaChat для пользователя {} (баланс: {}, max_tokens: {})", 
                    userId, balance, maxTokens);
            
            GigaMessageRequest rq = new GigaMessageRequest(
                    "GigaChat",
                    false,
                    0,
                    promptBuilder.buildMessages(userMessage),
                    1,
                    maxTokens,
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

            // --- Списание токенов ---
            int used = resp.getUsage() != null ? resp.getUsage().getTotalTokens() : 1;
            int newBalance = Math.max(balance - used, 0);
            user.setTokens(newBalance);
            userService.saveUser(user);
            log.info("Списано токенов: {}, было: {}, остаток: {}", used, balance, newBalance);

            // Формируем ответ с информацией об использованных токенах и остатке
            String responseText = resp.toString() + 
                    "\n\n💰 Потрачено токенов: " + used + 
                    "\n📊 Остаток токенов: " + newBalance +
                    FOOTER_INFO;
            
            log.info("Отправка ответа пользователю {}", userId);
            vkClient.sendMessage(userId, responseText);

            return ResponseEntity.ok("ok");
        }

        return ResponseEntity.ok("ok");
    }
}
