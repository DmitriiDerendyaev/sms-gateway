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
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.file.FileUploadResponse;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.Message;

import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
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
                        try {
                            handleAudioMessage(userId, peerId, attachment, externalMessageId);
                        } catch (Exception e) {
                            log.error("Ошибка при обработке голосового сообщения от пользователя {}: {}", userId, e.getMessage(), e);
                            vkClient.sendMessage(userId, "❌ Ошибка при обработке голосового сообщения. Попробуйте еще раз." + FOOTER_INFO);
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
                        "📱 Для оплаты выполните СБП(СберБанк) перевод на номер:\n" +
                        "   " + PAYMENT_PHONE + "\n\n" +
                        "🔑 В комментарии к переводу укажите код(без кавычек):\n" +
                        "   «" + paymentCode + "»\n\n" +
                        "✅ После оплаты токены будут автоматически начислены на ваш счет.\n " +
                        "Обратите внимание, что обработка платежа занимает до 10 минут.\n\n" +
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

        // Системный промпт для работы с аудио (оптимизирован для экономии токенов)
        // Сокращенная версия: убраны повторы и лишние слова, сохранен смысл
        // Исходный: "твоя задача внимательно слушать что говорит пользователь внимательно изучает ситуацию которую обозначают не нарушает никакие законы отвечать четко грамотно и ясно"
        String audioSystemPrompt = "Внимательно слушай, анализируй ситуацию, соблюдай законы, отвечай четко и грамотно";
        Message systemMessage = new Message("system", audioSystemPrompt);
        
        Message userMessage = new Message("user", "Расшифруй голосовое сообщение", attachments);
        List<Message> messages = new ArrayList<>();
        messages.add(systemMessage);
        messages.add(userMessage);

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

        // --- Формируем и отправляем ответ ---
        String responseText = response.toString() + 
                "\n\n💰 Потрачено токенов: " + used + 
                "\n📊 Остаток токенов: " + newBalance +
                FOOTER_INFO;

        log.info("Отправка ответа пользователю {}", userId);
        vkClient.sendMessage(userId, responseText);
    }
}
