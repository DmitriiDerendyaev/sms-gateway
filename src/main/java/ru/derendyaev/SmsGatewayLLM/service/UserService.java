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
    public String activatePromoWithPhone(Long telegramId, String username, String promoCode, String rawPhone) {
        String phone = normalizePhoneNumber(rawPhone);
        if (phone == null) {
            return "❌ Некорректный номер телефона.";
        }

        // Проверяем наличие промокода
        Optional<PromoCodeEntity> promoOpt = promoCodeRepository.findByCode(promoCode);
        if (promoOpt.isEmpty() || promoOpt.get().getIsUsed()) {
            return "❌ Промокод не найден или уже использован.";
        }
        PromoCodeEntity promo = promoOpt.get();

        // Ищем пользователя по Telegram ID
        Optional<UserEntity> userOpt = userRepository.findByTelegramId(telegramId);

        if (userOpt.isEmpty()) {
            // Пользователь впервые активирует промокод — создаём нового
            UserEntity newUser = new UserEntity();
            newUser.setTelegramId(telegramId);
            newUser.setUsername(username);
            newUser.setPhoneNumber(phone);
            newUser.setTokens(promo.getTokenAmount());
            userRepository.save(newUser);

            promo.setIsUsed(true);
            promoCodeRepository.save(promo);

            return "✅ Промокод активирован впервые! Телефон сохранён: +" + phone +
                    "\n💰 Начислено " + promo.getTokenAmount() + " токенов.";
        }

        // Если пользователь уже существует
        UserEntity user = userOpt.get();

        // Проверяем, совпадает ли телефон
        if (phone.equals(user.getPhoneNumber())) {
            // Телефон совпадает — просто начисляем токены
            user.setTokens(user.getTokens() + promo.getTokenAmount());
            userRepository.save(user);

            promo.setIsUsed(true);
            promoCodeRepository.save(promo);

            return "🎉 Промокод активирован повторно!\n💰 Начислено " + promo.getTokenAmount() +
                    " токенов. Текущий баланс: " + user.getTokens();
        }

        // Если телефон отличается, проверяем, есть ли этот телефон в БД
        Optional<UserEntity> phoneOwnerOpt = userRepository.findByPhoneNumber(phone);
        if (phoneOwnerOpt.isPresent()) {
            // Телефон принадлежит другому пользователю — начисляем токены ему
            UserEntity phoneOwner = phoneOwnerOpt.get();
            phoneOwner.setTokens(phoneOwner.getTokens() + promo.getTokenAmount());
            userRepository.save(phoneOwner);

            promo.setIsUsed(true);
            promoCodeRepository.save(promo);

            return "📲 Промокод активирован для пользователя с номером +" + phone +
                    "\n💰 Начислено " + promo.getTokenAmount() + " токенов.";
        }

        // Телефона нет в базе — ошибка
        return "❌ Указанный номер не найден среди зарегистрированных пользователей.";
    }

}
