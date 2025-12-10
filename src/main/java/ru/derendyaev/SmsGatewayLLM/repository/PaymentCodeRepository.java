package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.derendyaev.SmsGatewayLLM.model.PaymentCodeEntity;

import java.util.Optional;

@Repository
public interface PaymentCodeRepository extends JpaRepository<PaymentCodeEntity, Long> {
    Optional<PaymentCodeEntity> findByCodeAndIsUsedFalse(String code);
    boolean existsByCode(String code);
}

