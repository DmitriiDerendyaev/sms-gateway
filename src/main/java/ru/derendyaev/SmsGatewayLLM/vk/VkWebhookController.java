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
import ru.derendyaev.SmsGatewayLLM.service.EventParserService;
import ru.derendyaev.SmsGatewayLLM.service.GoogleCalendarService;
import ru.derendyaev.SmsGatewayLLM.service.MessageDeduplicationService;
import ru.derendyaev.SmsGatewayLLM.service.PaymentService;
import ru.derendyaev.SmsGatewayLLM.service.SmsService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;
import ru.derendyaev.SmsGatewayLLM.utils.PromptBuilder;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.file.FileUploadResponse;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.Message;

import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    private final EventParserService eventParserService;
    private final GoogleCalendarService googleCalendarService;

    @Value("${app.values.vk.group-id}")
    private String groupId;

    @Value("${app.values.vk.access-token}")
    private String accessToken;

    @Value("${app.values.google.server-host:derendyaev.ru}")
    private String serverHost;
    
    @Value("${app.values.google.server-protocol:https}")
    private String serverProtocol;

    private final VkClient vkClient; // создадим ниже

    // Хранение состояний пользователей VK
    // Возможные состояния:
    // - WAITING_PHONE - ожидание номера телефона
    // - CREATING_EVENT - создание события в Google Calendar
    // - IDLE - пользователь не создаёт событие (по умолчанию)
    private final Map<Integer, String> vkUserStates = new ConcurrentHashMap<>();
    
    // Константы состояний
    private static final String STATE_IDLE = "IDLE";
    private static final String STATE_WAITING_PHONE = "WAITING_PHONE";
    private static final String STATE_CREATING_EVENT = "CREATING_EVENT";
    private static final String STATE_WAITING_TIMEZONE = "WAITING_TIMEZONE";

    // Константы для кнопок
    private static final String BUTTON_CREATE_EVENT = "create_event_button";
    private static final String BUTTON_TIMEZONE_PLUS_2 = "timezone_plus_2";
    private static final String BUTTON_TIMEZONE_PLUS_3 = "timezone_plus_3";
    private static final String BUTTON_TIMEZONE_PLUS_4 = "timezone_plus_4";
    private static final String BUTTON_TEXT_CREATE_EVENT = "📅 Создать напоминание";
    
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            Integer peerId = (Integer) message.get("peer_id");

            log.info("Сообщение из ВК: userId={}, text='{}', messageId={}", userId, text, externalMessageId);
            log.debug("Полное сообщение: {}", message);

            // --- Обработка голосовых сообщений (ПЕРЕД обработкой текста) ---
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) message.get("attachments");
            if (attachments != null && !attachments.isEmpty()) {
                for (Map<String, Object> attachment : attachments) {
                    String attachmentType = (String) attachment.get("type");
                    if ("audio_message".equals(attachmentType)) {
                        log.info("Обнаружено голосовое сообщение от пользователя {}", userId);
                        
                        // --- Дедупликация для голосовых сообщений ---
                        // Используем специальный маркер "AUDIO_MESSAGE" как текст и messageId для дедупликации
                        String audioMessageText = "AUDIO_MESSAGE";
                        String userIdStr = String.valueOf(userId);
                        if (deduplicationService.isDuplicate(audioMessageText, userIdStr, externalMessageId)) {
                            log.info("Голосовое сообщение от пользователя {} с messageId {} уже обработано, пропускаем", userId, externalMessageId);
                            return ResponseEntity.ok("ok");
                        }
                        deduplicationService.registerMessage(audioMessageText, userIdStr, externalMessageId);
                        
                        try {
                            // Проверяем состояние пользователя
                            String currentState = vkUserStates.get(userId);
                            if (STATE_CREATING_EVENT.equals(currentState)) {
                                log.info("Обработка голосового сообщения в состоянии CREATING_EVENT для пользователя {}", userId);
                                handleAudioMessage(userId, peerId, attachment, externalMessageId);
                            } else {
                                handleAudioMessage(userId, peerId, attachment, externalMessageId);
                            }
                        } catch (Exception e) {
                            log.error("Ошибка при обработке голосового сообщения от пользователя {}: {}", userId, e.getMessage(), e);
                            vkClient.sendMessage(userId, "❌ Ошибка при обработке голосового сообщения. Попробуйте еще раз." + FOOTER_INFO);
                        }
                        return ResponseEntity.ok("ok");
                    } else if ("photo".equals(attachmentType)) {
                        log.info("Обнаружено фото от пользователя {}", userId);

                        String dedupText = "PHOTO_MESSAGE";
                        String userIdStr = String.valueOf(userId);

                        if (deduplicationService.isDuplicate(dedupText, userIdStr, externalMessageId)) {
                            log.info("Фото от пользователя {} уже обработано", userId);
                            return ResponseEntity.ok("ok");
                        }
                        deduplicationService.registerMessage(dedupText, userIdStr, externalMessageId);

                        try {
                            handlePhotoMessage(userId, peerId, attachment, text);
                        } catch (Exception e) {
                            log.error("Ошибка обработки фото", e);
                            vkClient.sendMessage(
                                    userId,
                                    "❌ Ошибка при обработке изображения. Попробуйте ещё раз." + FOOTER_INFO
                            );
                        }
                        return ResponseEntity.ok("ok");
                    }
                }
            }

            // --- Проверка на пустое сообщение ---
            if (text == null || text.trim().isEmpty()) {
                log.info("Получено пустое сообщение от пользователя {}", userId);
                vkClient.sendMessage(userId, "Пожалуйста, отправьте ваш вопрос или запрос" + FOOTER_INFO);
                return ResponseEntity.ok("ok");
            }

            String userMessage = text.trim();
            log.debug("Обработанное сообщение: '{}' (длина: {})", userMessage, userMessage.length());

            // --- Обработка кнопок (payload и текст кнопки) ---
            Object payloadObj = message.get("payload");
            if (payloadObj != null) {
                try {
                    String payloadStr;
                    if (payloadObj instanceof String) {
                        payloadStr = (String) payloadObj;
                    } else if (payloadObj instanceof Map) {
                        payloadStr = objectMapper.writeValueAsString(payloadObj);
                    } else {
                        payloadStr = payloadObj.toString();
                    }
                    
                    log.info("Получен payload от пользователя {}: {}", userId, payloadStr);
                    
                    // Парсим JSON payload
                    if (payloadStr.contains(BUTTON_CREATE_EVENT) || payloadStr.contains("\"button\":\"" + BUTTON_CREATE_EVENT + "\"")) {
                        log.info("Пользователь {} нажал кнопку создания события через payload", userId);
                        handleCreateEventButton(userId);
                        return ResponseEntity.ok("ok");
                    }

                    // Обработка кнопок выбора часового пояса
                    if (payloadStr.contains(BUTTON_TIMEZONE_PLUS_2) || payloadStr.contains("\"button\":\"" + BUTTON_TIMEZONE_PLUS_2 + "\"")) {
                        log.info("Пользователь {} выбрал timezone +2 через payload", userId);
                        handleTimezoneSelection(userId, BUTTON_TIMEZONE_PLUS_2);
                        return ResponseEntity.ok("ok");
                    }
                    if (payloadStr.contains(BUTTON_TIMEZONE_PLUS_3) || payloadStr.contains("\"button\":\"" + BUTTON_TIMEZONE_PLUS_3 + "\"")) {
                        log.info("Пользователь {} выбрал timezone +3 через payload", userId);
                        handleTimezoneSelection(userId, BUTTON_TIMEZONE_PLUS_3);
                        return ResponseEntity.ok("ok");
                    }
                    if (payloadStr.contains(BUTTON_TIMEZONE_PLUS_4) || payloadStr.contains("\"button\":\"" + BUTTON_TIMEZONE_PLUS_4 + "\"")) {
                        log.info("Пользователь {} выбрал timezone +4 через payload", userId);
                        handleTimezoneSelection(userId, BUTTON_TIMEZONE_PLUS_4);
                        return ResponseEntity.ok("ok");
                    }
                } catch (Exception e) {
                    log.warn("Ошибка при обработке payload от пользователя {}: {}", userId, e.getMessage());
                }
            }
            
            // Обработка текста кнопки (если пользователь просто отправил текст кнопки)
            if (BUTTON_TEXT_CREATE_EVENT.equals(userMessage)) {
                log.info("Пользователь {} отправил текст кнопки создания события", userId);
                handleCreateEventButton(userId);
                return ResponseEntity.ok("ok");
            }

            // --- Обработка команды /start (ПЕРЕД дедупликацией, чтобы команда всегда обрабатывалась) ---
            if ("/start".equalsIgnoreCase(userMessage) || "Начать".equalsIgnoreCase(userMessage)) {
                log.info("Получена команда /start от пользователя {}", userId);
                
                // Проверяем, зарегистрирован ли пользователь
                Optional<UserEntity> existingUserOpt = userService.getByVkId(userId);
                if (existingUserOpt.isPresent()) {
                    // Пользователь уже зарегистрирован - показываем краткую информацию
                    UserEntity user = existingUserOpt.get();
                    String infoMessage = "👋 С возвращением!\n\n" +
                            "🤖 Вы уже зарегистрированы в SmsGateway LLM.\n\n" +
                            "📊 Ваш баланс токенов: " + user.getTokens() + "\n\n" +
                            "💡 Доступные возможности:\n" +
                            "• Общение с нейросетью (просто отправьте сообщение)\n" +
                            "• Создание напоминаний в Google Calendar (кнопка ниже)\n" +
                            "• Активация промокодов: /promo <код>\n" +
                            "• Покупка токенов: /buy\n\n" +
                            "📅 Используйте кнопку ниже для создания напоминания!";
                    
                    vkClient.sendMessage(userId, infoMessage + FOOTER_INFO, createKeyboardJson());
                    vkUserStates.put(userId, STATE_IDLE);
                } else {
                    // Новый пользователь - регистрация
                    vkUserStates.put(userId, STATE_WAITING_PHONE);
                    vkClient.sendMessage(userId, WELCOME_MESSAGE + FOOTER_INFO, createKeyboardJson());
                }
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
                vkClient.sendMessage(userId, result + FOOTER_INFO, createKeyboardJson());
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
                        "📱 Для оплаты выполните СБП(СберБанк) перевод на номер:\n" +
                        "   " + PAYMENT_PHONE + "\n\n" +
                        "🔑 В комментарии к переводу укажите код(без кавычек):\n" +
                        "   «" + paymentCode + "»\n\n" +
                        "✅ После оплаты токены будут автоматически начислены на ваш счет.\n " +
                        "Обратите внимание, что обработка платежа занимает до 10 минут.\n\n" +
                        "⏱️ Код действителен в течение 24 часов.";
                
                vkClient.sendMessage(userId, buyMessage + FOOTER_INFO, createKeyboardJson());
                // Не регистрируем в дедупликации, чтобы можно было повторить покупку
                return ResponseEntity.ok("ok");
            }

            // --- Обработка состояний пользователя (ПЕРЕД дедупликацией) ---
            if (vkUserStates.containsKey(userId)) {
                String state = vkUserStates.get(userId);
                
                if (STATE_WAITING_PHONE.equals(state)) {
                    log.info("Пользователь {} в состоянии WAITING_PHONE, обрабатываем номер телефона", userId);
                    
                    // Проверяем, не зарегистрирован ли уже пользователь
                    Optional<UserEntity> existingUserOpt = userService.getByVkId(userId);
                    if (existingUserOpt.isPresent() && existingUserOpt.get().getPhoneNumber() != null 
                            && !existingUserOpt.get().getPhoneNumber().trim().isEmpty()) {
                        // Пользователь уже зарегистрирован
                        log.info("Пользователь {} уже зарегистрирован, переводим в IDLE", userId);
                        vkUserStates.put(userId, STATE_IDLE);
                        vkClient.sendMessage(userId,
                                "✅ Вы уже зарегистрированы!\n\n" +
                                "Используйте кнопку ниже для создания напоминания или отправьте сообщение для общения с нейросетью." + FOOTER_INFO,
                                createKeyboardJson());
                        return ResponseEntity.ok("ok");
                    }
                    
                    // Получаем username из сообщения (если доступно) или используем VK User ID
                    String username = null; // VK API не передаёт username напрямую в webhook
                    
                    // Регистрируем пользователя с телефоном
                    String result = userService.registerVkUserWithPhone(userId, username, userMessage);
                    vkClient.sendMessage(userId, result + FOOTER_INFO, createKeyboardJson());
                    vkUserStates.put(userId, STATE_IDLE);
                    log.info("Пользователь {} зарегистрирован с телефоном", userId);
                    // Не регистрируем в дедупликации, так как это одноразовое действие
                    return ResponseEntity.ok("ok");
                } else if (STATE_WAITING_TIMEZONE.equals(state)) {
                    log.info("Пользователь {} в состоянии WAITING_TIMEZONE, обрабатываем ввод timezone", userId);
                    handleManualTimezoneInput(userId, userMessage);
                    return ResponseEntity.ok("ok");
                } else if (STATE_CREATING_EVENT.equals(state)) {
                    log.info("Пользователь {} в состоянии CREATING_EVENT, обрабатываем описание события", userId);
                    return handleEventCreation(userId, userMessage, externalMessageId);
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
                                "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO,
                        createKeyboardJson());
                return ResponseEntity.ok("ok");
            }

            UserEntity user = userOpt.get();
            
            // --- Проверяем, что у пользователя есть привязанный номер телефона ---
            if (user.getPhoneNumber() == null || user.getPhoneNumber().trim().isEmpty()) {
                log.warn("У пользователя {} нет привязанного номера телефона", userId);
                vkClient.sendMessage(userId,
                        "❌ У вас нет привязанного номера телефона.\n\n" +
                                "Для регистрации отправьте команду /start и введите ваш номер телефона.\n\n" +
                                "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO,
                        createKeyboardJson());
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
                                "Свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO,
                        createKeyboardJson());
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
                        "❌ Ошибка LLM. Связь с админом: " + ADMIN_CONTACT + FOOTER_INFO,
                        createKeyboardJson());
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
            vkClient.sendMessage(userId, responseText, createKeyboardJson());

            return ResponseEntity.ok("ok");
        }

        return ResponseEntity.ok("ok");
    }

    private void handlePhotoMessage(
            Integer userId,
            Integer peerId,
            Map<String, Object> photoAttachment,
            String userText
    ) {
        log.info("Начало обработки фото от пользователя {}", userId);

        // --- Проверка пользователя ---
        Optional<UserEntity> userOpt = userService.getByVkId(userId);
        if (userOpt.isEmpty()) {
            vkClient.sendMessage(
                    userId,
                    "❌ Вы не зарегистрированы.\n\nВведите /start" + FOOTER_INFO
            );
            return;
        }

        UserEntity user = userOpt.get();
        int balance = user.getTokens();

        if (balance <= 0) {
            vkClient.sendMessage(
                    userId,
                    "⚠️ Недостаточно токенов." + FOOTER_INFO
            );
            return;
        }

        // --- Извлечение photo ---
        @SuppressWarnings("unchecked")
        Map<String, Object> photo = (Map<String, Object>) photoAttachment.get("photo");
        if (photo == null) {
            throw new RuntimeException("photo == null");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sizes =
                (List<Map<String, Object>>) photo.get("sizes");

        if (sizes == null || sizes.isEmpty()) {
            throw new RuntimeException("sizes пуст");
        }

        // --- Берём СРЕДНЮЮ по размеру (медиану) ---
        sizes.sort((a, b) -> {
            int aSize = (int) a.get("width") * (int) a.get("height");
            int bSize = (int) b.get("width") * (int) b.get("height");
            return Integer.compare(aSize, bSize);
        });

        Map<String, Object> mediumSize = sizes.get(sizes.size() / 2);
        String imageUrl = (String) mediumSize.get("url");

        log.info("Выбрана средняя картинка: {}", imageUrl);

        // --- Скачивание изображения ---
        RestTemplate restTemplate = new RestTemplate();
        byte[] imageBytes = restTemplate.getForObject(imageUrl, byte[].class);

        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("Изображение пустое");
        }

        long maxSize = 35L * 1024 * 1024;
        if (imageBytes.length > maxSize) {
            vkClient.sendMessage(
                    userId,
                    "❌ Размер изображения превышает 35 МБ." + FOOTER_INFO
            );
            return;
        }

        String filename = "image.jpg";

        // --- Загрузка в GigaChat ---
        FileUploadResponse uploadResponse =
                gigaChatClient.uploadFile(imageBytes, filename);

        String fileId = uploadResponse.getId();
        log.info("Фото загружено в GigaChat, file_id={}", fileId);

        // --- Формирование сообщений ---
        List<String> attachments = List.of(fileId);

        Message systemMessage = new Message(
                "system",
                "Ты анализируешь изображение и текст пользователя. Отвечай чётко и полезно."
        );

        String finalUserText =
                (userText == null || userText.isBlank())
                        ? "Проанализируй изображение"
                        : userText;

        Message userMessage = new Message(
                "user",
                finalUserText,
                attachments
        );

        List<Message> messages = List.of(systemMessage, userMessage);

        int maxTokens = Math.min(balance, 512);

        GigaMessageRequest request = new GigaMessageRequest(
                "GigaChat-Pro",
                false,
                0,
                messages,
                1,
                maxTokens,
                1.0
        );

        // --- Запрос в GigaChat ---
        GigaMessageResponse response = gigaChatClient.gigaMessageGenerate(request);

        int used = response.getUsage() != null
                ? response.getUsage().getTotalTokens()
                : 1;

        user.setTokens(Math.max(balance - used, 0));
        userService.saveUser(user);

        String responseText = response.toString() +
                "\n\n💰 Потрачено токенов: " + used +
                "\n📊 Остаток токенов: " + user.getTokens() +
                FOOTER_INFO;

        vkClient.sendMessage(userId, responseText);
    }


    /**
     * Обрабатывает голосовое сообщение от пользователя VK
     * @param userId ID пользователя VK
     * @param peerId peer_id из сообщения
     * @param audioAttachment объект attachment с типом "audio_message"
     * @param externalMessageId ID сообщения для дедупликации
     */
    private void handleAudioMessage(Integer userId, Integer peerId, Map<String, Object> audioAttachment, String externalMessageId) {
        log.info("Начало обработки голосового сообщения от пользователя {}", userId);

        // --- Проверяем регистрацию пользователя ---
        Optional<UserEntity> userOpt = userService.getByVkId(userId);
        if (userOpt.isEmpty()) {
            log.warn("Пользователь {} не найден в базе данных", userId);
            vkClient.sendMessage(userId,
                    "❌ Ваш аккаунт не зарегистрирован.\n\n" +
                            "Для регистрации отправьте команду /start и следуйте инструкциям.\n\n" +
                            "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO);
            return;
        }

        UserEntity user = userOpt.get();

        // --- Проверяем, что у пользователя есть привязанный номер телефона ---
        if (user.getPhoneNumber() == null || user.getPhoneNumber().trim().isEmpty()) {
            log.warn("У пользователя {} нет привязанного номера телефона", userId);
            vkClient.sendMessage(userId,
                    "❌ У вас нет привязанного номера телефона.\n\n" +
                            "Для регистрации отправьте команду /start и введите ваш номер телефона.\n\n" +
                            "Если у вас возникли проблемы, свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO);
            return;
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
            return;
        }

        // --- Извлекаем ссылку на аудиофайл ---
        @SuppressWarnings("unchecked")
        Map<String, Object> audioMessage = (Map<String, Object>) audioAttachment.get("audio_message");
        if (audioMessage == null) {
            log.error("Не удалось извлечь audio_message из attachment");
            vkClient.sendMessage(userId, "❌ Ошибка: не удалось обработать голосовое сообщение." + FOOTER_INFO);
            return;
        }

        String audioUrl = (String) audioMessage.get("link_mp3");
        if (audioUrl == null || audioUrl.isEmpty()) {
            audioUrl = (String) audioMessage.get("link_ogg");
        }

        if (audioUrl == null || audioUrl.isEmpty()) {
            log.error("Не найдена ссылка на аудиофайл (ни link_mp3, ни link_ogg)");
            vkClient.sendMessage(userId, "❌ Ошибка: не найдена ссылка на аудиофайл." + FOOTER_INFO);
            return;
        }

        log.info("Ссылка на аудиофайл: {}", audioUrl);

        // --- Скачиваем аудиофайл в память ---
        byte[] audioBytes;
        String filename;
        try {
            log.info("Скачивание аудиофайла...");
            RestTemplate restTemplate = new RestTemplate();
            audioBytes = restTemplate.getForObject(audioUrl, byte[].class);
            if (audioBytes == null || audioBytes.length == 0) {
                throw new RuntimeException("Скачанный файл пуст");
            }
            log.info("Аудиофайл скачан, размер: {} bytes ({} МБ)", audioBytes.length, audioBytes.length / (1024.0 * 1024.0));
            
            // --- Валидация размера файла (максимум 35 МБ для аудио) ---
            long maxSizeBytes = 35L * 1024 * 1024; // 35 МБ
            if (audioBytes.length > maxSizeBytes) {
                log.error("Превышен максимальный размер аудиофайла: {} bytes (максимум: {} bytes)", audioBytes.length, maxSizeBytes);
                vkClient.sendMessage(userId, 
                        "❌ Размер аудиофайла слишком большой (максимум 35 МБ).\n" +
                        "Попробуйте отправить более короткое голосовое сообщение." + FOOTER_INFO);
                return;
            }
            
            // --- Определение формата файла из URL ---
            String urlLower = audioUrl.toLowerCase();
            if (urlLower.contains(".mp3")) {
                filename = "audio.mp3";
            } else if (urlLower.contains(".m4a")) {
                filename = "audio.m4a";
            } else if (urlLower.contains(".wav")) {
                filename = "audio.wav";
            } else if (urlLower.contains(".ogg")) {
                filename = "audio.ogg";
            } else if (urlLower.contains(".opus")) {
                filename = "audio.opus";
            } else if (urlLower.contains(".webm") || urlLower.contains(".weba")) {
                filename = "audio.webm";
            } else {
                // По умолчанию используем ogg, так как VK часто использует этот формат
                filename = "audio.ogg";
                log.warn("Формат файла не определен из URL, используется ogg по умолчанию: {}", audioUrl);
            }
            log.info("Определен формат файла: {}", filename);
            
        } catch (Exception e) {
            log.error("Ошибка при скачивании аудиофайла: {}", e.getMessage(), e);
            vkClient.sendMessage(userId, "❌ Ошибка при скачивании аудиофайла. Попробуйте еще раз." + FOOTER_INFO);
            return;
        }

        // --- Загружаем файл в GigaChat ---
        FileUploadResponse fileUploadResponse;
        
        try {
            log.info("Загрузка файла в GigaChat: filename={}, size={} bytes", filename, audioBytes.length);
            fileUploadResponse = gigaChatClient.uploadFile(audioBytes, filename);
            log.info("✅ Файл успешно загружен в GigaChat, file_id: {}", fileUploadResponse.getId());
        } catch (Exception e) {
            log.error("❌ Ошибка при загрузке файла в GigaChat: {}", e.getMessage(), e);
            String errorMessage = "❌ Ошибка при загрузке файла в GigaChat.";
            if (e.getMessage() != null && e.getMessage().contains("422")) {
                errorMessage += "\n\nВозможные причины:\n" +
                        "• Неподдерживаемый формат файла\n" +
                        "• Превышен размер файла (максимум 35 МБ)\n" +
                        "• Проблема с форматом данных";
            }
            errorMessage += "\n\nПопробуйте еще раз или свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO;
            vkClient.sendMessage(userId, errorMessage);
            return;
        }

        // --- Отправляем запрос на расшифровку в GigaChat ---
        String fileId = fileUploadResponse.getId();
        List<String> attachments = new ArrayList<>();
        attachments.add(fileId);

        // Получаем текущее время для промпта
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));

        // Получаем timezone пользователя для локального времени
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        Integer timezoneOffset = null;
        ZonedDateTime userLocalTime = nowUtc;

        if (userOptTime.isPresent()) {
            timezoneOffset = userService.getTimezoneOffset(userOptTime.get());
            if (timezoneOffset != null) {
                userLocalTime = nowUtc.plusHours(timezoneOffset);
            }
        }

        String currentDateTime = userLocalTime.format(DateTimeFormatter.ISO_INSTANT);
        String currentDate = userLocalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String currentTime = userLocalTime.format(DateTimeFormatter.ofPattern("HH:mm"));

        String timezoneNote = (timezoneOffset != null) ?
                " (локальное время пользователя UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset + ")" :
                " (UTC)";

        // Определяем промпт в зависимости от состояния пользователя
        String state = vkUserStates.get(userId);
        String audioSystemPrompt;
        String audioUserPrompt;

        if (STATE_CREATING_EVENT.equals(state)) {
            // Специальный промпт для создания событий календаря с текущим временем
            audioSystemPrompt = "Ты - помощник для создания событий в Google Calendar. " +
                    "Пользователь отправил голосовое сообщение с описанием события. " +
                    "Твоя задача - РАСПОЗНАТЬ текст из голосового сообщения и вернуть ТОЛЬКО описание события в текстовом формате, " +
                    "подходящем для создания напоминания в календаре.\n\n" +
                    "КРИТИЧЕСКИ ВАЖНО - ТЕКУЩЕЕ ВРЕМЯ" + timezoneNote + ":\n" +
                    "Сейчас: " + currentDateTime + "\n" +
                    "Сегодня: " + currentDate + ", время: " + currentTime + timezoneNote.replace(" (", "").replace(")", "") + "\n\n" +
                    "ПРАВИЛА:\n" +
                    "- Верни ТОЛЬКО текст описания события\n" +
                    "- Не добавляй лишние комментарии или вопросы\n" +
                    "- Не пытайся создать JSON или структурировать данные\n" +
                    "- Просто верни то, что пользователь сказал голосом\n" +
                    "- Учитывай, что относительное время ('через 1 час', 'завтра') рассчитывается от ТЕКУЩЕГО момента\n\n" +
                    "ПРИМЕРЫ:\n" +
                    "Пользователь говорит: \"Создать напоминание через 1 час\"\n" +
                    "Ты отвечаешь: \"Создать напоминание через 1 час\"\n\n" +
                    "Пользователь говорит: \"Встреча с командой завтра в 10 часов\"\n" +
                    "Ты отвечаешь: \"Встреча с командой завтра в 10 часов\"";

            audioUserPrompt = "Распознай текст голосового сообщения и верни только описание события для календаря. Текущее время: " + currentDateTime;
        } else {
            // Обычный промпт для общего общения
            audioSystemPrompt = "Внимательно слушай, анализируй ситуацию, соблюдай законы, отвечай четко и грамотно";
            audioUserPrompt = "Распознай текст, Помоги пользователю в решении его задачи. Не переспрашивай пользователя, пытайся ответить сам";
        }

        Message systemMessage = new Message("system", audioSystemPrompt);
        Message userMessageObj = new Message("user", audioUserPrompt, attachments);
        List<Message> messages = new ArrayList<>();
        messages.add(systemMessage);
        messages.add(userMessageObj);

        int maxTokens = Math.min(balance, 512);
        log.info("Отправка запроса на расшифровку в GigaChat для пользователя {} (баланс: {}, max_tokens: {})", 
                userId, balance, maxTokens);

        // Используем модель с поддержкой мультимодальности для работы с аудио
        String modelName = "GigaChat-Pro"; // Модель с поддержкой работы с файлами
        
        GigaMessageRequest request = new GigaMessageRequest(
                modelName,
                false,
                0,
                messages,
                1,
                maxTokens,
                1.0
        );

        log.info("Запрос на распознавание: model={}, file_id={}, max_tokens={}", 
                modelName, fileId, maxTokens);
        log.debug("Полный запрос: {}", request);

        GigaMessageResponse response;
        try {
            response = gigaChatClient.gigaMessageGenerate(request);
            log.info("✅ Получен ответ от GigaChat для пользователя {}", userId);
            if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                log.debug("Текст распознавания: {}", response.getChoices().get(0).getMessage().getContent());
            }
        } catch (Exception e) {
            log.error("❌ Ошибка при запросе к GigaChat для пользователя {}: {}", userId, e.getMessage(), e);
            String errorMessage = "❌ Ошибка при распознавании голосового сообщения.";
            
            // Специальная обработка ошибки 422
            if (e.getMessage() != null && e.getMessage().contains("422")) {
                errorMessage += "\n\nВозможные причины:\n" +
                        "• Модель не поддерживает работу с аудиофайлами\n" +
                        "• Превышен размер контекста модели\n" +
                        "• Некорректная структура запроса\n" +
                        "• Файл не был корректно загружен";
                log.error("⚠️ Ошибка 422 (Unprocessable Entity) - проблема валидации запроса");
            }
            
            errorMessage += "\n\nСвязь с админом: " + ADMIN_CONTACT + FOOTER_INFO;
            vkClient.sendMessage(userId, errorMessage);
            return;
        }

        // --- Списание токенов ---
        int used = response.getUsage() != null ? response.getUsage().getTotalTokens() : 1;
        int newBalance = Math.max(balance - used, 0);
        user.setTokens(newBalance);
        userService.saveUser(user);
        log.info("Списано токенов: {}, было: {}, остаток: {}", used, balance, newBalance);

        // Проверяем состояние пользователя ДО обработки ответа
        String userState = vkUserStates.get(userId);

        if (STATE_CREATING_EVENT.equals(userState)) {
            // Специальная обработка для режима создания событий
            String transcribedText = response.getChoices().get(0).getMessage().getContent().trim();
            log.info("Голосовое сообщение транскрибировано в состоянии CREATING_EVENT: {}", transcribedText);

            // Очищаем от возможных markdown-форматирований
            transcribedText = transcribedText.replaceAll("```", "").trim();

            // НЕ списываем токены здесь - они спишутся в handleEventCreation при парсинге
            // Возвращаем токены назад, так как это промежуточный шаг
            user.setTokens(balance); // Возвращаем баланс к исходному
            userService.saveUser(user);
            log.info("Токены возвращены пользователю {} (промежуточный шаг создания события)", userId);

            // Передаем распознанный текст на создание события
            handleEventCreation(userId, transcribedText, externalMessageId);
        } else {
            // Обычная обработка голосового сообщения
            String responseText = response.toString() +
                    "\n\n💰 Потрачено токенов: " + used +
                    "\n📊 Остаток токенов: " + newBalance +
                    FOOTER_INFO;

            log.info("Отправка ответа пользователю {}", userId);
            vkClient.sendMessage(userId, responseText);
        }
    }

    /**
     * Создаёт JSON-строку с клавиатурой VK для кнопки "Создать напоминание".
     *
     * @return JSON-строка с клавиатурой
     */
    private String createKeyboardJson() {
        // Формат клавиатуры VK API v5.199
        return "{\"one_time\":false,\"buttons\":[[{\"action\":{\"type\":\"text\",\"label\":\"📅 Создать напоминание\",\"payload\":\"{\\\"button\\\":\\\"" + BUTTON_CREATE_EVENT + "\\\"}\"},\"color\":\"primary\"}]]}";
    }

    /**
     * Создаёт JSON-строку с клавиатурой VK для выбора часового пояса.
     *
     * @return JSON-строка с клавиатурой выбора timezone
     */
    private String createTimezoneKeyboardJson() {
        // Формат клавиатуры VK API v5.199 с кнопками выбора часового пояса
        return "{\"one_time\":false,\"buttons\":[" +
                "[{\"action\":{\"type\":\"text\",\"label\":\"➕2 часа\",\"payload\":\"{\\\"button\\\":\\\"" + BUTTON_TIMEZONE_PLUS_2 + "\\\"}\"},\"color\":\"primary\"}," +
                "{\"action\":{\"type\":\"text\",\"label\":\"➕3 часа\",\"payload\":\"{\\\"button\\\":\\\"" + BUTTON_TIMEZONE_PLUS_3 + "\\\"}\"},\"color\":\"primary\"}," +
                "{\"action\":{\"type\":\"text\",\"label\":\"➕4 часа\",\"payload\":\"{\\\"button\\\":\\\"" + BUTTON_TIMEZONE_PLUS_4 + "\\\"}\"},\"color\":\"primary\"}]]}";
    }

    /**
     * Обрабатывает нажатие кнопки "Создать напоминание".
     * 
     * @param userId ID пользователя VK
     */
    private void handleCreateEventButton(Integer userId) {
        log.info("Обработка нажатия кнопки создания события для пользователя {}", userId);

        // Проверяем регистрацию пользователя
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        if (userOptTime.isEmpty()) {
            vkClient.sendMessage(userId,
                    "❌ Вы не зарегистрированы.\n\n" +
                    "Для регистрации отправьте команду /start и следуйте инструкциям." + FOOTER_INFO,
                    createKeyboardJson());
            return;
        }

        UserEntity user = userOptTime.get();

        // Проверяем, установлен ли часовой пояс
        if (!userService.hasTimezone(user)) {
            log.info("Часовой пояс не установлен для пользователя {}, запрашиваем выбор", userId);
            requestTimezoneSelection(userId);
            return;
        }

        // Проверяем Google-авторизацию
        if (!googleCalendarService.hasValidAuth(user.getId())) {
            // Формируем URL для авторизации
            String authUrl = String.format("%s://%s/oauth/google/authorize?vkUserId=%d",
                    serverProtocol, serverHost, userId);

            vkClient.sendMessage(userId,
                    "🔐 Требуется авторизация Google\n\n" +
                    "Для создания событий в Google Calendar необходимо авторизоваться через Google.\n\n" +
                    "📎 Перейдите по ссылке для авторизации:\n" +
                    authUrl + "\n\n" +
                    "После авторизации вы сможете создавать напоминания в Google Calendar.",
                    createKeyboardJson());
            return;
        }

        // Переводим пользователя в состояние создания события
        vkUserStates.put(userId, STATE_CREATING_EVENT);

        String message = "📅 Создание напоминания\n\n" +
                "Опишите событие, которое вы хотите создать в Google Calendar.\n\n" +
                "Примеры:\n" +
                "• Встреча с командой завтра в 10:00\n" +
                "• Собеседование 25 декабря в 15:30\n" +
                "• Позвонить маме через 2 часа\n\n" +
                "Вы можете отправить текст или голосовое сообщение.";

        vkClient.sendMessage(userId, message + FOOTER_INFO, createKeyboardJson());
    }

    /**
     * Запрашивает выбор часового пояса у пользователя
     */
    private void requestTimezoneSelection(Integer userId) {
        vkUserStates.put(userId, STATE_WAITING_TIMEZONE);

        String message = "🌍 Выбор часового пояса\n\n" +
                "Для корректного создания напоминаний необходимо указать ваш часовой пояс.\n" +
                "Выберите смещение от UTC или введите вручную (например: +3 или -5).\n\n" +
                "Часовой пояс устанавливается один раз и используется для всех напоминаний.";

        vkClient.sendMessage(userId, message + FOOTER_INFO, createTimezoneKeyboardJson());
    }

    /**
     * Обрабатывает выбор часового пояса через кнопки
     */
    private void handleTimezoneSelection(Integer userId, String buttonPayload) {
        log.info("Обработка выбора часового пояса для пользователя {}: {}", userId, buttonPayload);

        Integer timezoneOffset = null;
        switch (buttonPayload) {
            case BUTTON_TIMEZONE_PLUS_2:
                timezoneOffset = 2;
                break;
            case BUTTON_TIMEZONE_PLUS_3:
                timezoneOffset = 3;
                break;
            case BUTTON_TIMEZONE_PLUS_4:
                timezoneOffset = 4;
                break;
            default:
                log.warn("Неизвестная кнопка timezone для пользователя {}: {}", userId, buttonPayload);
                vkClient.sendMessage(userId, "❌ Неизвестная кнопка. Попробуйте еще раз." + FOOTER_INFO, createKeyboardJson());
                return;
        }

        // Сохраняем timezone и переводим в состояние создания события
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        if (userOptTime.isPresent()) {
            UserEntity user = userOptTime.get();
            try {
                userService.setTimezone(user, timezoneOffset);

                vkClient.sendMessage(userId,
                        "✅ Часовой пояс установлен: UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset + "\n\n" +
                        "Теперь вы можете создавать напоминания!" + FOOTER_INFO,
                        createKeyboardJson());

                // Переходим к созданию события
                proceedToEventCreation(userId);
            } catch (IllegalArgumentException e) {
                log.error("Ошибка установки timezone для пользователя {}: {}", userId, e.getMessage());
                vkClient.sendMessage(userId, "❌ Ошибка установки часового пояса. Попробуйте еще раз." + FOOTER_INFO, createKeyboardJson());
            }
        }
    }

    /**
     * Обрабатывает ручной ввод часового пояса
     */
    private void handleManualTimezoneInput(Integer userId, String timezoneText) {
        log.info("Обработка ручного ввода timezone для пользователя {}: {}", userId, timezoneText);

        Integer timezoneOffset = userService.parseTimezoneOffset(timezoneText.trim());

        if (timezoneOffset == null) {
            vkClient.sendMessage(userId,
                    "❌ Некорректный формат часового пояса.\n\n" +
                    "Используйте формат: +2, +3, -5, +4\n" +
                    "Или выберите из предложенных кнопок." + FOOTER_INFO,
                    createTimezoneKeyboardJson());
            return;
        }

        // Сохраняем timezone
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        if (userOptTime.isPresent()) {
            UserEntity user = userOptTime.get();
            try {
                userService.setTimezone(user, timezoneOffset);

                vkClient.sendMessage(userId,
                        "✅ Часовой пояс установлен: UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset + "\n\n" +
                        "Теперь вы можете создавать напоминания!" + FOOTER_INFO,
                        createKeyboardJson());

                // Переходим к созданию события
                proceedToEventCreation(userId);
            } catch (IllegalArgumentException e) {
                vkClient.sendMessage(userId,
                        "❌ Ошибка установки часового пояса. Попробуйте еще раз." + FOOTER_INFO,
                        createTimezoneKeyboardJson());
            }
        }
    }

    /**
     * Продолжает процесс создания события после установки timezone
     */
    private void proceedToEventCreation(Integer userId) {
        log.info("Продолжение создания события для пользователя {} после установки timezone", userId);

        // Проверяем Google-авторизацию
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        if (userOptTime.isPresent()) {
            UserEntity user = userOptTime.get();

            if (!googleCalendarService.hasValidAuth(user.getId())) {
                // Формируем URL для авторизации
                String authUrl = String.format("%s://%s/oauth/google/authorize?vkUserId=%d",
                        serverProtocol, serverHost, userId);

                vkClient.sendMessage(userId,
                        "🔐 Требуется авторизация Google\n\n" +
                        "Для создания событий в Google Calendar необходимо авторизоваться через Google.\n\n" +
                        "📎 Перейдите по ссылке для авторизации:\n" +
                        authUrl + "\n\n" +
                        "После авторизации вы сможете создавать напоминания в Google Calendar.",
                        createKeyboardJson());
                return;
            }

            // Переводим пользователя в состояние создания события
            vkUserStates.put(userId, STATE_CREATING_EVENT);

            String message = "📅 Создание напоминания\n\n" +
                    "Опишите событие, которое вы хотите создать в Google Calendar.\n\n" +
                    "Примеры:\n" +
                    "• Встреча с командой завтра в 10:00\n" +
                    "• Собеседование 25 декабря в 15:30\n" +
                    "• Позвонить маме через 2 часа\n\n" +
                    "Вы можете отправить текст или голосовое сообщение.";

            vkClient.sendMessage(userId, message + FOOTER_INFO, createKeyboardJson());
        }
    }

    /**
     * Обрабатывает создание события из текста пользователя.
     *
     * @param userId ID пользователя VK
     * @param userText Текст с описанием события
     * @param externalMessageId ID сообщения для дедупликации (может быть null для голосовых)
     * @return ResponseEntity
     */
    private ResponseEntity<String> handleEventCreation(Integer userId, String userText, String externalMessageId) {
        log.info("Обработка создания события для пользователя {}: {}", userId, userText);

        // Дедупликация (только если есть externalMessageId - для текстовых сообщений)
        if (externalMessageId != null && deduplicationService.isDuplicate(userText, String.valueOf(userId), externalMessageId)) {
            log.debug("Сообщение от пользователя {} является дубликатом, пропускаем", userId);
            return ResponseEntity.ok("ok");
        }
        if (externalMessageId != null) {
            deduplicationService.registerMessage(userText, String.valueOf(userId), externalMessageId);
        }
        
        // Проверяем регистрацию пользователя
        Optional<UserEntity> userOptTime = userService.getByVkId(userId);
        if (userOptTime.isEmpty()) {
            vkClient.sendMessage(userId,
                    "❌ Вы не зарегистрированы.\n\n" +
                    "Для регистрации отправьте команду /start и следуйте инструкциям." + FOOTER_INFO,
                    createKeyboardJson());
            vkUserStates.put(userId, STATE_IDLE);
            return ResponseEntity.ok("ok");
        }

        UserEntity user = userOptTime.get();
        
        // Проверяем баланс токенов
        int balance = user.getTokens();
        if (balance <= 0) {
            vkClient.sendMessage(userId,
                    "⚠️ Недостаточно токенов.\n\n" +
                    "Ваш текущий баланс: " + balance + " токенов.\n" +
                    "Пополните баланс для продолжения работы.\n\n" +
                    "Свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO,
                    createKeyboardJson());
            vkUserStates.put(userId, STATE_IDLE);
            return ResponseEntity.ok("ok");
        }

        // Проверяем Google-авторизацию
        if (!googleCalendarService.hasValidAuth(user.getId())) {
            // Формируем URL для авторизации
            String authUrl = String.format("%s://%s/oauth/google/authorize?vkUserId=%d", 
                    serverProtocol, serverHost, userId);
            
            vkClient.sendMessage(userId,
                    "🔐 Требуется авторизация Google\n\n" +
                    "Для создания событий в Google Calendar необходимо авторизоваться через Google.\n\n" +
                    "📎 Перейдите по ссылке для авторизации:\n" +
                    authUrl + "\n\n" +
                    "После авторизации попробуйте создать событие снова.",
                    createKeyboardJson());
            vkUserStates.put(userId, STATE_IDLE);
            return ResponseEntity.ok("ok");
        }

        try {
            // Парсим текст в JSON для Google Calendar с учетом timezone пользователя
            log.info("Парсинг текста в JSON для события: {}", userText);
            Integer timezoneOffset = userService.getTimezoneOffset(user);
            String eventJson = eventParserService.parseTextToEventJson(userText, timezoneOffset);
            
            if (eventJson == null) {
                log.error("Не удалось распарсить текст в JSON для события");
                vkClient.sendMessage(userId,
                        "❌ Не удалось обработать описание события.\n\n" +
                        "Попробуйте описать событие более подробно, указав дату и время.\n\n" +
                        "Пример: \"Встреча с командой завтра в 10:00\"" + FOOTER_INFO,
                        createKeyboardJson());
                vkUserStates.put(userId, STATE_IDLE);
                return ResponseEntity.ok("ok");
            }

            log.info("Текст успешно распарсен в JSON: {}", eventJson);

            // Списание токенов за парсинг текста в JSON (для голосовых сообщений)
            // Для текстовых сообщений токены спишутся в основном обработчике
            int tokensAfterParsing = user.getTokens();
            if (externalMessageId == null) { // Это голосовое сообщение
                // Списываем токены за парсинг (дополнительно к уже списанным за транскрибирование)
                int parsingTokensUsed = 1; // Примерное значение, можно улучшить
                tokensAfterParsing = Math.max(user.getTokens() - parsingTokensUsed, 0);
                user.setTokens(tokensAfterParsing);
                userService.saveUser(user);
                log.info("Дополнительно списано токенов за парсинг: {}, итоговый баланс: {}", parsingTokensUsed, tokensAfterParsing);
            }

            // Создаём событие в Google Calendar
            String result = googleCalendarService.createEvent(user, eventJson);
            
            // Отправляем результат пользователю
            vkClient.sendMessage(userId, result + FOOTER_INFO, createKeyboardJson());
            
            // Возвращаем пользователя в состояние IDLE
            vkUserStates.put(userId, STATE_IDLE);
            
            log.info("Событие успешно создано для пользователя {}", userId);
            
        } catch (Exception e) {
            log.error("Ошибка при создании события для пользователя {}: {}", userId, e.getMessage(), e);
            vkClient.sendMessage(userId,
                    "❌ Ошибка при создании события: " + e.getMessage() + "\n\n" +
                    "Попробуйте еще раз или свяжитесь с администратором: " + ADMIN_CONTACT + FOOTER_INFO,
                    createKeyboardJson());
            vkUserStates.put(userId, STATE_IDLE);
        }

        return ResponseEntity.ok("ok");
    }

}
