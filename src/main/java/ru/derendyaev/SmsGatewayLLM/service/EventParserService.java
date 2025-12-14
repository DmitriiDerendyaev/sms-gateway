package ru.derendyaev.SmsGatewayLLM.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.Message;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервис для парсинга текста пользователя в JSON-формат для создания события Google Calendar.
 * Использует GigaChat для извлечения структурированных данных из текста.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventParserService {

    private final GigaChatClient gigaChatClient;
    private final Gson gson = new Gson();

    /**
     * Парсит текст пользователя в JSON-формат для Google Calendar.
     * 
     * @param userText Текст пользователя с описанием события
     * @return JSON-строка с данными события или null в случае ошибки
     */
    public String parseTextToEventJson(String userText) {
        log.info("Начало парсинга текста в JSON для события: {}", userText);

        try {
            // Формируем промпт для GigaChat
            String systemPrompt = "Ты - помощник для создания событий в календаре. " +
                    "Пользователь отправляет текст с описанием события (например: 'Встреча с командой завтра в 10:00', " +
                    "'Собеседование 25 декабря в 15:30', 'Позвонить маме через 2 часа'). " +
                    "Твоя задача - извлечь из текста структурированные данные и вернуть ТОЛЬКО валидный JSON в следующем формате:\n" +
                    "{\n" +
                    "  \"summary\": \"Название события\",\n" +
                    "  \"description\": \"Описание события (может быть пустым)\",\n" +
                    "  \"start\": {\n" +
                    "    \"dateTime\": \"2024-12-25T15:30:00Z\",\n" +
                    "    \"timeZone\": \"UTC\"\n" +
                    "  },\n" +
                    "  \"end\": {\n" +
                    "    \"dateTime\": \"2024-12-25T16:30:00Z\",\n" +
                    "    \"timeZone\": \"UTC\"\n" +
                    "  },\n" +
                    "  \"attendees\": [\n" +
                    "    {\"email\": \"user@example.com\"}\n" +
                    "  ],\n" +
                    "  \"reminders\": {\n" +
                    "    \"useDefault\": false,\n" +
                    "    \"overrides\": [\n" +
                    "      {\"method\": \"email\", \"minutes\": 30},\n" +
                    "      {\"method\": \"popup\", \"minutes\": 10}\n" +
                    "    ]\n" +
                    "  }\n" +
                    "}\n\n" +
                    "ВАЖНО:\n" +
                    "1. Все даты и время должны быть в формате ISO 8601 в UTC (например: 2024-12-25T15:30:00Z)\n" +
                    "2. Если время не указано, используй текущее время + 1 час для начала\n" +
                    "3. Если дата не указана, используй сегодняшнюю дату\n" +
                    "4. Продолжительность события по умолчанию - 1 час\n" +
                    "5. Поля attendees и reminders могут быть пустыми массивами, если не указаны\n" +
                    "6. Верни ТОЛЬКО JSON, без дополнительных комментариев или текста\n" +
                    "7. Текущая дата и время: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            Message systemMessage = new Message("system", systemPrompt);
            Message userMessage = new Message("user", userText);

            List<Message> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.add(userMessage);

            GigaMessageRequest request = new GigaMessageRequest(
                    "GigaChat",
                    false,
                    0,
                    messages,
                    1,
                    512,
                    1.0
            );

            log.debug("Отправка запроса в GigaChat для парсинга события");
            GigaMessageResponse response = gigaChatClient.gigaMessageGenerate(request);

            if (response.getChoices() == null || response.getChoices().isEmpty()) {
                log.error("GigaChat вернул пустой ответ");
                return null;
            }

            String jsonText = response.getChoices().get(0).getMessage().getContent().trim();
            log.info("Получен JSON от GigaChat: {}", jsonText);

            // Очищаем JSON от возможных markdown-форматирований
            jsonText = jsonText.replaceAll("```json", "").replaceAll("```", "").trim();

            // Валидируем JSON
            try {
                JsonObject jsonObject = gson.fromJson(jsonText, JsonObject.class);
                log.info("JSON успешно валидирован");
                
                // Убеждаемся, что есть обязательные поля
                if (!jsonObject.has("summary")) {
                    log.warn("JSON не содержит поле 'summary', добавляем значение по умолчанию");
                    jsonObject.addProperty("summary", "Событие");
                }
                
                // Убеждаемся, что есть start и end
                if (!jsonObject.has("start")) {
                    log.warn("JSON не содержит поле 'start', добавляем значение по умолчанию");
                    JsonObject start = new JsonObject();
                    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
                    start.addProperty("dateTime", now.format(DateTimeFormatter.ISO_INSTANT));
                    start.addProperty("timeZone", "UTC");
                    jsonObject.add("start", start);
                }
                
                if (!jsonObject.has("end")) {
                    log.warn("JSON не содержит поле 'end', добавляем значение по умолчанию (start + 1 час)");
                    JsonObject start = jsonObject.getAsJsonObject("start");
                    String startDateTime = start.get("dateTime").getAsString();
                    ZonedDateTime startTime = ZonedDateTime.parse(startDateTime);
                    ZonedDateTime endTime = startTime.plusHours(1);
                    
                    JsonObject end = new JsonObject();
                    end.addProperty("dateTime", endTime.format(DateTimeFormatter.ISO_INSTANT));
                    end.addProperty("timeZone", "UTC");
                    jsonObject.add("end", end);
                }
                
                return gson.toJson(jsonObject);
            } catch (Exception e) {
                log.error("Ошибка валидации JSON: {}", e.getMessage(), e);
                return null;
            }

        } catch (Exception e) {
            log.error("Ошибка при парсинге текста в JSON: {}", e.getMessage(), e);
            return null;
        }
    }
}

