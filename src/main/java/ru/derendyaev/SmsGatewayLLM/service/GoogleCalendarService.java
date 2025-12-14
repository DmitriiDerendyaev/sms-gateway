package ru.derendyaev.SmsGatewayLLM.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.derendyaev.SmsGatewayLLM.model.CalendarEventEntity;
import ru.derendyaev.SmsGatewayLLM.model.GoogleAuthentificationEntity;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.repository.CalendarEventRepository;
import ru.derendyaev.SmsGatewayLLM.repository.GoogleAuthentificationRepository;
import ru.derendyaev.SmsGatewayLLM.service.UserService;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис для работы с Google Calendar API.
 * Обеспечивает создание событий, управление токенами и сохранение событий в БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCalendarService {

    private final GoogleAuthentificationRepository googleAuthRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final UserService userService;
    private final Gson gson = new Gson();

    @Value("${app.values.google.client-id:}")
    private String clientId;

    @Value("${app.values.google.client-secret:}")
    private String clientSecret;

    private static final String APPLICATION_NAME = "SmsGatewayLLM";
    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    /**
     * Проверяет наличие Google-авторизации для пользователя.
     * 
     * @param userId ID пользователя
     * @return true, если авторизация существует и токен валиден
     */
    public boolean hasValidAuth(Long userId) {
        Optional<GoogleAuthentificationEntity> authOpt = googleAuthRepository.findByUserId(userId);
        if (authOpt.isEmpty()) {
            log.info("Google-авторизация не найдена для пользователя {}", userId);
            return false;
        }

        GoogleAuthentificationEntity auth = authOpt.get();
        
        // Проверяем, не истёк ли токен
        if (auth.getTokenExpiry().isBefore(LocalDateTime.now())) {
            log.info("Токен истёк для пользователя {}, требуется обновление", userId);
            return refreshToken(auth);
        }

        return true;
    }

    /**
     * Обновляет access token используя refresh token.
     * 
     * @param auth Сущность авторизации
     * @return true, если обновление успешно
     */
    private boolean refreshToken(GoogleAuthentificationEntity auth) {
        try {
            log.info("Обновление токена для пользователя {}", auth.getUserId());
            
            // Используем HTTP запрос для обновления токена через Google OAuth2 API
            // Формируем запрос на обновление токена
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.util.MultiValueMap<String, String> params = new org.springframework.util.LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("refresh_token", auth.getRefreshToken());
            params.add("grant_type", "refresh_token");
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> request = 
                    new org.springframework.http.HttpEntity<>(params, headers);
            
            String tokenUrl = "https://oauth2.googleapis.com/token";
            @SuppressWarnings("unchecked")
            org.springframework.http.ResponseEntity<Map<String, Object>> response = 
                    restTemplate.postForEntity(tokenUrl, request, (Class<Map<String, Object>>) (Class<?>) Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> tokenResponse = response.getBody();
                String newAccessToken = (String) tokenResponse.get("access_token");
                Integer expiresIn = (Integer) tokenResponse.get("expires_in");
                
                if (newAccessToken != null && expiresIn != null) {
                    auth.setAccessToken(newAccessToken);
                    auth.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
                    auth.setUpdatedAt(LocalDateTime.now());
                    googleAuthRepository.save(auth);
                    
                    log.info("Токен успешно обновлён для пользователя {}", auth.getUserId());
                    return true;
                }
            }
            
            log.error("Не удалось обновить токен: неверный ответ от сервера");
            return false;
        } catch (Exception e) {
            log.error("Ошибка при обновлении токена для пользователя {}: {}", auth.getUserId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Получает валидный access token для пользователя.
     * 
     * @param userId ID пользователя
     * @return access token или null, если не удалось получить
     */
    private String getValidAccessToken(Long userId) {
        Optional<GoogleAuthentificationEntity> authOpt = googleAuthRepository.findByUserId(userId);
        if (authOpt.isEmpty()) {
            return null;
        }

        GoogleAuthentificationEntity auth = authOpt.get();
        
        // Если токен истёк, обновляем его
        if (auth.getTokenExpiry().isBefore(LocalDateTime.now())) {
            if (!refreshToken(auth)) {
                return null;
            }
            // Перезагружаем из БД после обновления
            auth = googleAuthRepository.findByUserId(userId).orElse(null);
            if (auth == null) {
                return null;
            }
        }

        return auth.getAccessToken();
    }

    /**
     * Создаёт клиент Google Calendar API для пользователя.
     * 
     * @param userId ID пользователя
     * @return Calendar клиент или null, если не удалось создать
     */
    private Calendar getCalendarClient(Long userId) {
        String accessToken = getValidAccessToken(userId);
        if (accessToken == null) {
            log.error("Не удалось получить валидный access token для пользователя {}", userId);
            return null;
        }

        try {
            // Создаём Credential из access token
            Credential credential = new Credential.Builder(
                    com.google.api.client.auth.oauth2.BearerToken.authorizationHeaderAccessMethod())
                    .setTransport(HTTP_TRANSPORT)
                    .setJsonFactory(JSON_FACTORY)
                    .build();
            credential.setAccessToken(accessToken);

            return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        } catch (Exception e) {
            log.error("Ошибка при создании Calendar клиента для пользователя {}: {}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Создаёт событие в Google Calendar из JSON-строки.
     *
     * @param user Пользователь
     * @param eventJson JSON-строка с данными события
     * @param tokensUsed Количество токенов, потраченных на создание события
     * @return Результат создания события (ID события или сообщение об ошибке)
     */
    @Transactional
    public String createEvent(UserEntity user, String eventJson, int tokensUsed) {
        log.info("Создание события в Google Calendar для пользователя {}: {}", user.getId(), eventJson);

        // Проверяем авторизацию
        if (!hasValidAuth(user.getId())) {
            return "❌ Google-авторизация отсутствует или недействительна. Пожалуйста, пройдите авторизацию через Google OAuth.";
        }

        try {
            // Парсим JSON
            JsonObject eventData = gson.fromJson(eventJson, JsonObject.class);
            
            // Создаём Calendar клиент
            Calendar calendar = getCalendarClient(user.getId());
            if (calendar == null) {
                return "❌ Ошибка при подключении к Google Calendar. Проверьте авторизацию.";
            }

            // Создаём объект Event
            Event event = new Event();
            
            // Summary
            if (eventData.has("summary")) {
                event.setSummary(eventData.get("summary").getAsString());
            }
            
            // Description
            if (eventData.has("description")) {
                event.setDescription(eventData.get("description").getAsString());
            }
            
            // Start
            if (eventData.has("start")) {
                JsonObject startObj = eventData.getAsJsonObject("start");
                EventDateTime start = new EventDateTime();
                if (startObj.has("dateTime")) {
                    start.setDateTime(com.google.api.client.util.DateTime.parseRfc3339(
                            startObj.get("dateTime").getAsString()
                    ));
                }
                if (startObj.has("timeZone")) {
                    start.setTimeZone(startObj.get("timeZone").getAsString());
                }
                event.setStart(start);
            }
            
            // End
            if (eventData.has("end")) {
                JsonObject endObj = eventData.getAsJsonObject("end");
                EventDateTime end = new EventDateTime();
                if (endObj.has("dateTime")) {
                    end.setDateTime(com.google.api.client.util.DateTime.parseRfc3339(
                            endObj.get("dateTime").getAsString()
                    ));
                }
                if (endObj.has("timeZone")) {
                    end.setTimeZone(endObj.get("timeZone").getAsString());
                }
                event.setEnd(end);
            }
            
            // Attendees
            if (eventData.has("attendees") && eventData.get("attendees").isJsonArray()) {
                JsonArray attendeesArray = eventData.getAsJsonArray("attendees");
                List<EventAttendee> attendees = new java.util.ArrayList<>();
                for (int i = 0; i < attendeesArray.size(); i++) {
                    JsonObject attendeeObj = attendeesArray.get(i).getAsJsonObject();
                    if (attendeeObj.has("email")) {
                        EventAttendee attendee = new EventAttendee();
                        attendee.setEmail(attendeeObj.get("email").getAsString());
                        attendees.add(attendee);
                    }
                }
                if (!attendees.isEmpty()) {
                    event.setAttendees(attendees);
                }
            }
            
            // Reminders
            if (eventData.has("reminders")) {
                JsonObject remindersObj = eventData.getAsJsonObject("reminders");
                Event.Reminders reminders = new Event.Reminders();
                if (remindersObj.has("useDefault")) {
                    reminders.setUseDefault(remindersObj.get("useDefault").getAsBoolean());
                }
                if (remindersObj.has("overrides") && remindersObj.get("overrides").isJsonArray()) {
                    JsonArray overridesArray = remindersObj.getAsJsonArray("overrides");
                    List<EventReminder> overrides = new java.util.ArrayList<>();
                    for (int i = 0; i < overridesArray.size(); i++) {
                        JsonObject overrideObj = overridesArray.get(i).getAsJsonObject();
                        EventReminder reminder = new EventReminder();
                        if (overrideObj.has("method")) {
                            reminder.setMethod(overrideObj.get("method").getAsString());
                        }
                        if (overrideObj.has("minutes")) {
                            reminder.setMinutes(overrideObj.get("minutes").getAsInt());
                        }
                        overrides.add(reminder);
                    }
                    reminders.setOverrides(overrides);
                }
                event.setReminders(reminders);
            }

            // Создаём событие в Google Calendar
            Event createdEvent = calendar.events().insert("primary", event).execute();
            String googleEventId = createdEvent.getId();
            log.info("Событие успешно создано в Google Calendar: {}", googleEventId);

            // Получаем timezone пользователя для отображения
            Integer userTimezoneOffset = userService.getTimezoneOffset(user);

            // Извлекаем время начала и окончания из созданного события
            String startTimeStr = formatEventTime(createdEvent.getStart(), userTimezoneOffset);
            String endTimeStr = formatEventTime(createdEvent.getEnd(), userTimezoneOffset);

            // Если время не удалось отформатировать, используем fallback из оригинального JSON
            if ("Не указано".equals(startTimeStr) || "Не указано".equals(endTimeStr)) {
                startTimeStr = extractTimeFromOriginalJson(eventData, "start", userTimezoneOffset);
                endTimeStr = extractTimeFromOriginalJson(eventData, "end", userTimezoneOffset);
            }

            // Сохраняем событие в БД
            CalendarEventEntity calendarEvent = CalendarEventEntity.builder()
                    .userId(user.getId())
                    .googleEventId(googleEventId)
                    .summary(event.getSummary())
                    .description(event.getDescription())
                    .startTime(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(createdEvent.getStart().getDateTime() != null ?
                                    createdEvent.getStart().getDateTime().getValue() :
                                    createdEvent.getStart().getDate().getValue()),
                            ZoneId.of(createdEvent.getStart().getTimeZone() != null ? createdEvent.getStart().getTimeZone() : "UTC")
                    ))
                    .endTime(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(createdEvent.getEnd().getDateTime() != null ?
                                    createdEvent.getEnd().getDateTime().getValue() :
                                    createdEvent.getEnd().getDate().getValue()),
                            ZoneId.of(createdEvent.getEnd().getTimeZone() != null ? createdEvent.getEnd().getTimeZone() : "UTC")
                    ))
                    .timezone(createdEvent.getStart().getTimeZone() != null ? createdEvent.getStart().getTimeZone() : "UTC")
                    .attendees(eventData.has("attendees") ? eventData.get("attendees").toString() : null)
                    .reminders(eventData.has("reminders") ? eventData.get("reminders").toString() : null)
                    .build();

            calendarEventRepository.save(calendarEvent);
            log.info("Событие сохранено в БД: {}", calendarEvent.getId());

            // Получаем текущий баланс токенов пользователя
            int currentTokenBalance = user.getTokens();
            int previousBalance = currentTokenBalance + tokensUsed;

            return "✅ Событие успешно создано в Google Calendar!\n\n" +
                   "📅 " + (event.getSummary() != null ? event.getSummary() : "Без названия") + "\n" +
                   "🕐 " + startTimeStr + (startTimeStr.equals(endTimeStr) ? "" : " - " + endTimeStr) + "\n" +
                   "💰 Потрачено токенов: " + tokensUsed + " | Остаток: " + currentTokenBalance + "\n" +
                   "🔗 ID: " + googleEventId;

        } catch (IOException e) {
            log.error("Ошибка при создании события в Google Calendar: {}", e.getMessage(), e);
            return "❌ Ошибка при создании события: " + e.getMessage();
        } catch (Exception e) {
            log.error("Неожиданная ошибка при создании события: {}", e.getMessage(), e);
            return "❌ Ошибка при создании события: " + e.getMessage();
        }
    }

    /**
     * Форматирует время события для отображения пользователю.
     */
    private String formatEventTime(EventDateTime eventDateTime, Integer userTimezoneOffset) {
        if (eventDateTime == null) {
            return "Не указано";
        }

        try {
            ZoneId displayZoneId = getDisplayZoneId(userTimezoneOffset);

            // Если есть dateTime (точное время)
            if (eventDateTime.getDateTime() != null) {
                com.google.api.client.util.DateTime dateTime = eventDateTime.getDateTime();
                Instant instant = Instant.ofEpochMilli(dateTime.getValue());
                ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(instant, displayZoneId);

                // Форматируем в человекочитаемый вид
                return zonedDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy в HH:mm")) +
                       " " + getTimezoneDisplay(userTimezoneOffset);
            }
            // Если есть только date (весь день)
            else if (eventDateTime.getDate() != null) {
                com.google.api.client.util.DateTime date = eventDateTime.getDate();
                Instant instant = Instant.ofEpochMilli(date.getValue());
                ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(instant, displayZoneId);

                return zonedDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy (весь день)"));
            }
            else {
                return "Не указано";
            }
        } catch (Exception e) {
            log.warn("Не удалось отформатировать время события: {}", e.getMessage());
            return "Не указано";
        }
    }

    /**
     * Сохраняет данные Google-авторизации для пользователя.
     * 
     * @param userId ID пользователя
     * @param accessToken Access token
     * @param refreshToken Refresh token
     * @param expiresIn Срок действия токена в секундах
     */
    @Transactional
    public void saveAuth(Long userId, String accessToken, String refreshToken, long expiresIn) {
        log.info("Сохранение Google-авторизации для пользователя {}", userId);
        
        Optional<GoogleAuthentificationEntity> existingAuthOpt = googleAuthRepository.findByUserId(userId);
        
        GoogleAuthentificationEntity auth;
        if (existingAuthOpt.isPresent()) {
            auth = existingAuthOpt.get();
        } else {
            auth = GoogleAuthentificationEntity.builder()
                    .userId(userId)
                    .build();
        }
        
        auth.setAccessToken(accessToken);
        auth.setRefreshToken(refreshToken);
        auth.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
        auth.setUpdatedAt(LocalDateTime.now());
        
        googleAuthRepository.save(auth);
        log.info("Google-авторизация сохранена для пользователя {}", userId);
    }

    /**
     * Извлекает время из оригинального JSON для fallback отображения
     */
    private String extractTimeFromOriginalJson(JsonObject eventData, String fieldName, Integer userTimezoneOffset) {
        try {
            if (eventData.has(fieldName)) {
                JsonObject timeObj = eventData.getAsJsonObject(fieldName);
                if (timeObj.has("dateTime")) {
                    String dateTimeStr = timeObj.get("dateTime").getAsString();
                    // Парсим время из строки вида "2025-12-15T13:00:00"
                    if (dateTimeStr.length() >= 16) {
                        String date = dateTimeStr.substring(0, 10);
                        String time = dateTimeStr.substring(11, 16);
                        // Преобразуем в формат dd.MM.yyyy в HH:mm с timezone
                        String[] dateParts = date.split("-");
                        return String.format("%s.%s.%s в %s %s",
                                           dateParts[2], dateParts[1], dateParts[0], time,
                                           getTimezoneDisplay(userTimezoneOffset));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Не удалось извлечь время из JSON: {}", e.getMessage());
        }
        return "Не указано";
    }

    /**
     * Получает ZoneId для отображения времени пользователя
     */
    private ZoneId getDisplayZoneId(Integer timezoneOffset) {
        if (timezoneOffset == null) {
            return ZoneId.of("UTC");
        }

        // Создаем ZoneId с нужным смещением от UTC
        return ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofHours(timezoneOffset));
    }

    /**
     * Получает строковое представление часового пояса для отображения
     */
    private String getTimezoneDisplay(Integer timezoneOffset) {
        if (timezoneOffset == null) {
            return "UTC";
        }

        if (timezoneOffset == 0) {
            return "UTC";
        }

        String sign = timezoneOffset > 0 ? "+" : "";
        return "UTC" + sign + timezoneOffset;
    }
}

