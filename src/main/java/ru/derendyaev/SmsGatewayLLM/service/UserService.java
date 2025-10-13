package ru.derendyaev.SmsGatewayLLM.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.derendyaev.SmsGatewayLLM.model.PromoCodeEntity;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.repository.PromoCodeRepository;
import ru.derendyaev.SmsGatewayLLM.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PromoCodeRepository promoCodeRepository;

    // ===================== Пользователи =====================

    public Optional<UserEntity> getByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    public Optional<UserEntity> getByPhoneNumber(String rawPhone) {
        String phone = normalizePhoneNumber(rawPhone);
        if (phone == null) return Optional.empty();
        return userRepository.findByPhoneNumber(phone);
    }

    @Transactional
    public UserEntity saveUser(UserEntity user) {
        if (user.getPhoneNumber() != null) {
            user.setPhoneNumber(normalizePhoneNumber(user.getPhoneNumber()));
        }
        return userRepository.save(user);
    }


    public boolean hasTokens(String phoneNumber) {
        return userRepository.findByPhoneNumber(phoneNumber)
                .map(u -> u.getTokens() > 0)
                .orElse(false);
    }

    public String normalizePhoneNumber(String rawPhone) {
        if (rawPhone == null) return null;

        // Оставляем только цифры
        String digits = rawPhone.replaceAll("\\D", "");

        // Проверяем, что длина >= 10
        if (digits.length() < 10) return null;

        // Берем последние 10 цифр
        return digits.substring(digits.length() - 10);
    }


    // ===================== Промокоды =====================

    /**
     * Проверяет, существует ли промокод и не активирован ли он.
     */
    public boolean checkPromoExists(String code) {
        return promoCodeRepository.findByCodeAndIsUsedFalse(code).isPresent();
    }

    /**
     * Генерация указанного количества промокодов с заданным количеством токенов.
     */
    @Transactional
    public String generatePromoCodes(int count, int tokenAmount) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            promoCodeRepository.save(PromoCodeEntity.builder()
                    .code(code)
                    .tokenAmount(tokenAmount)
                    .build());
            codes.append(code).append("\n");
        }
        return codes.toString();
    }

    /**
     * Активация промокода (старый вариант без телефона).
     */
    @Transactional
    public String activatePromo(Long telegramId, String code) {
        PromoCodeEntity promo = promoCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("Промокод не найден"));

        if (promo.getIsUsed()) return "Промокод уже активирован.";

        UserEntity user = userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> userRepository.save(UserEntity.builder()
                        .telegramId(telegramId)
                        .tokens(0)
                        .build()));

        user.setTokens(user.getTokens() + promo.getTokenAmount());
        promo.setIsUsed(true);
        promo.setUsedBy(telegramId);

        userRepository.save(user);
        promoCodeRepository.save(promo);

        return "✅ Промокод активирован! Вам начислено " + promo.getTokenAmount() + " токенов.";
    }

    /**
     * Активация промокода с указанием номера телефона.
     */
    @Transactional
    public String activatePromoWithPhone(Long telegramId, String username, String code, String phoneNumber) {
        Optional<PromoCodeEntity> promoOpt = promoCodeRepository.findByCodeAndIsUsedFalse(code);
        if (promoOpt.isEmpty()) {
            return "❌ Промокод не найден или уже активирован.";
        }

        PromoCodeEntity promo = promoOpt.get();

        // Проверяем, есть ли пользователь
        UserEntity user = userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> userRepository.save(UserEntity.builder()
                        .telegramId(telegramId)
                        .username(username)
                        .phoneNumber(normalizePhoneNumber(phoneNumber))
                        .tokens(0)
                        .build()));

        // Обновляем данные
        user.setPhoneNumber(phoneNumber);
        user.setUsername(username);
        user.setTokens(user.getTokens() + promo.getTokenAmount());
        userRepository.save(user);

        // Помечаем промокод использованным
        promo.setIsUsed(true);
        promo.setUsedBy(telegramId);
        promoCodeRepository.save(promo);

        return "✅ Промокод успешно активирован!\n📱 Телефон: " + phoneNumber +
                "\n💰 Начислено токенов: " + promo.getTokenAmount();
    }
}
