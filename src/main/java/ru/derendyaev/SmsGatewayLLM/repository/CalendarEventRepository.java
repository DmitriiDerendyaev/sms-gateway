package ru.derendyaev.SmsGatewayLLM.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.derendyaev.SmsGatewayLLM.model.CalendarEventEntity;

import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEventEntity, Long> {
    List<CalendarEventEntity> findByUserId(Long userId);
    Optional<CalendarEventEntity> findByGoogleEventId(String googleEventId);
    boolean existsByGoogleEventId(String googleEventId);
}

