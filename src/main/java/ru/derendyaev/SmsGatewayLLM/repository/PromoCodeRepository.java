package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.derendyaev.SmsGatewayLLM.model.PromoCodeEntity;

import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCodeEntity, Long> {
    Optional<PromoCodeEntity> findByCode(String code);
    Optional<PromoCodeEntity> findByCodeAndIsUsedFalse(String code);

}
