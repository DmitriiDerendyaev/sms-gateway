package ru.derendyaev.SmsGatewayLLM.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.derendyaev.SmsGatewayLLM.model.PromoCodeEntity;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.repository.PromoCodeRepository;
import ru.derendyaev.SmsGatewayLLM.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PromoCodeRepository promoCodeRepository;

    // ===================== Пользователи =====================

    public Optional<UserEntity> getByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    public Optional<UserEntity> getByVkId(Integer vkUserId) {
        return userRepository.findByVkUserId(vkUserId);
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

    /**
     * Регистрация или обновление VK пользователя с номером телефона.
     * Если пользователя с таким телефоном нет - создаёт нового.
     * Если есть - добавляет VK User ID к существующему пользователю.
     * 
     * @param vkUserId VK User ID
     * @param username Имя пользователя (может быть null)
     * @param rawPhone Номер телефона в любом формате
     * @return Сообщение о результате регистрации
     */
    @Transactional
    public String registerVkUserWithPhone(Integer vkUserId, String username, String rawPhone) {
        String phone = normalizePhoneNumber(rawPhone);
        if (phone == null) {
            return "❌ Некорректный номер телефона. Пожалуйста, введите номер в формате: +7XXXXXXXXXX или 8XXXXXXXXXX";
        }

        // Ищем пользователя по номеру телефона
        Optional<UserEntity> userByPhoneOpt = userRepository.findByPhoneNumber(phone);

        if (userByPhoneOpt.isEmpty()) {
            // Пользователя с таким телефоном нет - создаём нового
            UserEntity newUser = UserEntity.builder()
                    .vkUserId(vkUserId)
                    .phoneNumber(phone)
                    .username(username != null ? username : "vk_" + vkUserId)
                    .tokens(5000) // Начальный бонус при регистрации
                    .createdAt(LocalDateTime.now())
                    .build();
            userRepository.save(newUser);
            log.info("Создан новый VK пользователь: vkUserId={}, phone={}, username={}, tokens=5000", vkUserId, phone, username);
            return "✅ Регистрация успешна! Ваш номер телефона: 8" + phone +
                   "\n\n💰 Вам начислено 5000 токенов в подарок!" +
                   "\n\nТеперь вы можете использовать бота для взаимодействия с нейросетью.";
        } else {
            // Пользователь с таким телефоном уже есть - добавляем только VK User ID
            UserEntity existingUser = userByPhoneOpt.get();
            existingUser.setVkUserId(vkUserId);
            // username не изменяем, оставляем существующий
            userRepository.save(existingUser);
            log.info("Обновлён существующий пользователь: добавлен vkUserId={} для phone={}, username остался прежним: {}", 
                    vkUserId, phone, existingUser.getUsername());
            return "✅ Ваш VK аккаунт успешно привязан к номеру телефона: +" + phone + 
                   "\n\nТеперь вы можете использовать бота для взаимодействия с нейросетью.";
        }
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
            promo.setUsedBy(telegramId);
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

    /**
     * Активация промокода для VK пользователя по VK User ID.
     * Начисляет токены пользователю, если промокод валиден.
     * 
     * @param vkUserId VK User ID
     * @param promoCode Код промокода
     * @return Сообщение о результате активации
     */
    @Transactional
    public String activatePromoForVkUser(Integer vkUserId, String promoCode) {
        // Проверяем наличие промокода
        Optional<PromoCodeEntity> promoOpt = promoCodeRepository.findByCode(promoCode);
        if (promoOpt.isEmpty() || promoOpt.get().getIsUsed()) {
            return "❌ Промокод не найден или уже использован.";
        }
        PromoCodeEntity promo = promoOpt.get();

        // Ищем пользователя по VK ID
        Optional<UserEntity> userOpt = userRepository.findByVkUserId(vkUserId);
        if (userOpt.isEmpty()) {
            return "❌ Пользователь не найден. Пожалуйста, сначала зарегистрируйтесь командой /start.";
        }

        UserEntity user = userOpt.get();
        
        // Начисляем токены
        user.setTokens(user.getTokens() + promo.getTokenAmount());
        userRepository.save(user);

        // Помечаем промокод как использованный
        promo.setIsUsed(true);
        promoCodeRepository.save(promo);

        log.info("Промокод {} активирован для VK пользователя {}: начислено {} токенов, баланс: {}", 
                promoCode, vkUserId, promo.getTokenAmount(), user.getTokens());

        return "✅ Промокод активирован!\n💰 Начислено " + promo.getTokenAmount() +
               " токенов.\n📊 Текущий баланс: " + user.getTokens() + " токенов.";
    }

    // ===================== Часовые пояса =====================

    /**
     * Проверяет, установлен ли часовой пояс у пользователя
     */
    public boolean hasTimezone(UserEntity user) {
        return user.getTimezoneOffset() != null;
    }

    /**
     * Устанавливает часовой пояс для пользователя
     */
    @Transactional
    public void setTimezone(UserEntity user, Integer timezoneOffset) {
        if (timezoneOffset < -12 || timezoneOffset > 14) {
            throw new IllegalArgumentException("Смещение часового пояса должно быть в диапазоне от -12 до +14 часов");
        }

        user.setTimezoneOffset(timezoneOffset);
        user.setTimezoneSetAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Установлен часовой пояс для пользователя {}: UTC{:+d}", user.getVkUserId(), timezoneOffset);
    }

    /**
     * Получает смещение часового пояса пользователя в часах
     * Возвращает null, если часовой пояс не установлен
     */
    public Integer getTimezoneOffset(UserEntity user) {
        return user.getTimezoneOffset();
    }

    /**
     * Парсит строку с часовым поясом и возвращает смещение от UTC
     * Поддерживаемые форматы: "+2", "+3", "-5", "+4"
     */
    public Integer parseTimezoneOffset(String timezoneStr) {
        if (timezoneStr == null || timezoneStr.trim().isEmpty()) {
            return null;
        }

        String trimmed = timezoneStr.trim();

        // Паттерн для форматов типа "+2", "-5"
        Pattern pattern = Pattern.compile("^([+-])(\\d+)$");
        var matcher = pattern.matcher(trimmed);

        if (matcher.matches()) {
            String sign = matcher.group(1);
            int hours = Integer.parseInt(matcher.group(2));

            if (hours < 0 || hours > 14) {
                return null; // Недопустимое значение часов
            }

            return sign.equals("+") ? hours : -hours;
        }

        return null; // Не удалось распарсить
    }

}
