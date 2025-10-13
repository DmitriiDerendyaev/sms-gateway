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

    public Optional<UserEntity> getByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    public boolean hasTokens(String phoneNumber) {
        return userRepository.findByPhoneNumber(phoneNumber)
                .map(u -> u.getTokens() > 0)
                .orElse(false);
    }

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
}
