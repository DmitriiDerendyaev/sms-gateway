package ru.derendyaev.SmsGatewayLLM.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.derendyaev.SmsGatewayLLM.model.PaymentCodeEntity;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.repository.PaymentCodeRepository;
import ru.derendyaev.SmsGatewayLLM.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentCodeRepository paymentCodeRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Исключаем похожие символы (0, O, I, 1)
    private static final int CODE_LENGTH = 4;
    private static final int TOKENS_PER_RUBLE = 100;

    /**
     * Генерирует уникальный 4-символьный код для оплаты.
     */
    @Transactional
    public String generatePaymentCode(Integer vkUserId) {
        String code;
        int attempts = 0;
        do {
            code = generateRandomCode();
            attempts++;
            if (attempts > 100) {
                throw new RuntimeException("Не удалось сгенерировать уникальный код после 100 попыток");
            }
        } while (paymentCodeRepository.existsByCode(code));

        PaymentCodeEntity paymentCode = PaymentCodeEntity.builder()
                .code(code)
                .vkUserId(vkUserId)
                .isUsed(false)
                .createdAt(LocalDateTime.now())
                .build();

        paymentCodeRepository.save(paymentCode);
        log.info("Сгенерирован платежный код {} для пользователя VK {}", code, vkUserId);
        return code;
    }

    /**
     * Результат обработки платежа.
     */
    public static class PaymentResult {
        private final Integer vkUserId;
        private final int tokensAdded;
        private final double amount;
        private final int newBalance;

        public PaymentResult(Integer vkUserId, int tokensAdded, double amount, int newBalance) {
            this.vkUserId = vkUserId;
            this.tokensAdded = tokensAdded;
            this.amount = amount;
            this.newBalance = newBalance;
        }

        public Integer getVkUserId() {
            return vkUserId;
        }

        public int getTokensAdded() {
            return tokensAdded;
        }

        public double getAmount() {
            return amount;
        }

        public int getNewBalance() {
            return newBalance;
        }
    }

    /**
     * Обрабатывает платеж по коду и сумме из SMS.
     * Парсит сообщение, находит код и сумму, начисляет токены.
     * @return PaymentResult с информацией о платеже или null, если обработка не удалась
     */
    @Transactional
    public PaymentResult processPayment(String smsMessage) {
        log.info("Обработка платежа из SMS: {}", smsMessage);

        // Парсим код из сообщения (ищем код в кавычках или в конце)
        String code = extractCodeFromMessage(smsMessage);
        if (code == null || code.length() != CODE_LENGTH) {
            log.warn("Не удалось извлечь код из сообщения: {}", smsMessage);
            return null;
        }

        // Парсим сумму из сообщения
        Double amount = extractAmountFromMessage(smsMessage);
        if (amount == null || amount <= 0) {
            log.warn("Не удалось извлечь сумму из сообщения: {}", smsMessage);
            return null;
        }

        log.info("Извлечено из SMS: код={}, сумма={} руб.", code, amount);

        // Ищем платежный код
        Optional<PaymentCodeEntity> paymentCodeOpt = paymentCodeRepository.findByCodeAndIsUsedFalse(code);
        if (paymentCodeOpt.isEmpty()) {
            log.warn("Платежный код {} не найден или уже использован", code);
            return null;
        }

        PaymentCodeEntity paymentCode = paymentCodeOpt.get();

        // Находим пользователя
        Optional<UserEntity> userOpt = userRepository.findByVkUserId(paymentCode.getVkUserId());
        if (userOpt.isEmpty()) {
            log.warn("Пользователь VK {} не найден для платежного кода {}", paymentCode.getVkUserId(), code);
            return null;
        }

        UserEntity user = userOpt.get();

        // Начисляем токены (1 рубль = 100 токенов)
        int tokensToAdd = (int) (amount * TOKENS_PER_RUBLE);
        int oldBalance = user.getTokens();
        user.setTokens(oldBalance + tokensToAdd);
        userRepository.save(user);

        // Помечаем код как использованный
        paymentCode.setIsUsed(true);
        paymentCode.setUsedAt(LocalDateTime.now());
        paymentCodeRepository.save(paymentCode);

        log.info("Платеж обработан: код={}, сумма={} руб., начислено токенов={}, баланс: {} -> {}", 
                code, amount, tokensToAdd, oldBalance, user.getTokens());

        return new PaymentResult(paymentCode.getVkUserId(), tokensToAdd, amount, user.getTokens());
    }

    /**
     * Извлекает 4-символьный код из SMS сообщения.
     * Ищет код в кавычках «...» или в конце сообщения.
     */
    private String extractCodeFromMessage(String message) {
        if (message == null) return null;

        // Ищем код в кавычках «...»
        int startQuote = message.indexOf('«');
        int endQuote = message.indexOf('»');
        if (startQuote != -1 && endQuote != -1 && endQuote > startQuote) {
            String code = message.substring(startQuote + 1, endQuote).trim();
            if (code.length() == CODE_LENGTH) {
                return code.toUpperCase();
            }
        }

        // Ищем код в конце сообщения (последние 4 символа, если они буквы/цифры)
        String trimmed = message.trim();
        if (trimmed.length() >= CODE_LENGTH) {
            String lastPart = trimmed.substring(trimmed.length() - CODE_LENGTH).trim();
            if (lastPart.matches("[A-Z0-9]{" + CODE_LENGTH + "}")) {
                return lastPart.toUpperCase();
            }
        }

        return null;
    }

    /**
     * Извлекает сумму платежа из SMS сообщения.
     * Ищет паттерны типа "+10р", "+10 руб", "10.50р" и т.д.
     */
    private Double extractAmountFromMessage(String message) {
        if (message == null) return null;

        // Ищем паттерны: +10р, +10.50р, +10 руб, 10р, 10.50р и т.д.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "[+]?([0-9]+(?:[.,][0-9]+)?)\\s*(?:р|руб|рублей|₽)"
        );
        java.util.regex.Matcher matcher = pattern.matcher(message);
        
        if (matcher.find()) {
            try {
                String amountStr = matcher.group(1).replace(',', '.');
                return Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                log.warn("Ошибка парсинга суммы: {}", matcher.group(1));
            }
        }

        return null;
    }

    /**
     * Генерирует случайный код из разрешенных символов.
     */
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return code.toString();
    }
}

