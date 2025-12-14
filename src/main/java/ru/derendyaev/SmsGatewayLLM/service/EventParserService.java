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
import java.time.format.DateTimeParseException;
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
     * Использует UTC время (для обратной совместимости).
     *
     * @param userText Текст пользователя с описанием события
     * @return JSON-строка с данными события или null в случае ошибки
     */
    public String parseTextToEventJson(String userText) {
        return parseTextToEventJson(userText, null);
    }

    /**
     * Парсит текст пользователя в JSON-формат для Google Calendar.
     * GigaChat сам интерпретирует время и возвращает готовый JSON.
     *
     * @param userText Текст пользователя с описанием события
     * @param timezoneOffset Смещение часового пояса пользователя от UTC в часах (может быть null)
     * @return JSON-строка с данными события или null в случае ошибки
     */
    public String parseTextToEventJson(String userText, Integer timezoneOffset) {
        log.info("Начало парсинга текста в JSON для события: {}", userText);

        try {
            // Получаем текущее время в UTC
            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));

            // Рассчитываем локальное время пользователя
            ZonedDateTime userLocalTime = (timezoneOffset != null) ?
                    nowUtc.plusHours(timezoneOffset) : nowUtc;

            // Получаем название часового пояса для GigaChat
            String timezoneName = getTimezoneName(timezoneOffset);

            // Форматируем локальное время для передачи в GigaChat (без UTC)
            String currentLocalDateTime = userLocalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            String currentDate = userLocalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String currentTime = userLocalTime.format(DateTimeFormatter.ofPattern("HH:mm"));

            // Определяем день недели
            String dayOfWeek = userLocalTime.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.forLanguageTag("ru")));

            // Формируем промпт для GigaChat
            String systemPrompt = "Ты — помощник для создания событий в Google Calendar.\n\n" +
                    "Пользователь отправляет текст с описанием события.\n" +
                    "Твоя задача:\n" +
                    "1. Распознать текст\n" +
                    "2. Самостоятельно определить ТОЧНУЮ дату и время события\n" +
                    "3. Вернуть JSON, максимально близкий к формату Google Calendar\n\n" +
                    "ТЕКУЩИЙ КОНТЕКСТ:\n" +
                    "- Текущее локальное время пользователя: " + currentLocalDateTime + "\n" +
                    "- Часовой пояс пользователя: " + timezoneName + "\n" +
                    "- Сегодня: " + dayOfWeek + " (" + currentDate + ")\n\n" +
                    "ПРАВИЛА:\n" +
                    "- Ты ОБЯЗАН самостоятельно интерпретировать выражения времени:\n" +
                    "  (\"завтра\", \"в обед\", \"вечером\", \"через 2 часа\" и т.д.)\n" +
                    "- Используй локальное время пользователя\n" +
                    "- Если время указано не точно (\"в обед\", \"вечером\"):\n" +
                    "  - обед = 13:00\n" +
                    "  - утро = 09:00\n" +
                    "  - вечер = 19:00\n" +
                    "  - ночь = 23:00\n" +
                    "- Если длительность не указана — ставь 1 час\n" +
                    "- Формат dateTime: YYYY-MM-DDTHH:mm:ss (без секунд в конце, если они 00)\n\n" +
                    "ФОРМАТ ОТВЕТА (ТОЛЬКО JSON):\n" +
                    "{\n" +
                    "  \"summary\": \"Название события\",\n" +
                    "  \"description\": \"\",\n" +
                    "  \"start\": {\n" +
                    "    \"dateTime\": \"YYYY-MM-DDTHH:mm:ss\",\n" +
                    "    \"timeZone\": \"" + timezoneName + "\"\n" +
                    "  },\n" +
                    "  \"end\": {\n" +
                    "    \"dateTime\": \"YYYY-MM-DDTHH:mm:ss\",\n" +
                    "    \"timeZone\": \"" + timezoneName + "\"\n" +
                    "  }\n" +
                    "}\n\n" +
                    "ЗАПРЕЩЕНО:\n" +
                    "- markdown\n" +
                    "- комментарии\n" +
                    "- пояснения\n" +
                    "- текст вне JSON";

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

            // Валидируем и конвертируем JSON
            return validateAndConvertJson(jsonText, timezoneOffset);

        } catch (Exception e) {
            log.error("Ошибка при парсинге текста в JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Получает название часового пояса по смещению
     */
    private String getTimezoneName(Integer timezoneOffset) {
        if (timezoneOffset == null) {
            return "UTC";
        }

        return switch (timezoneOffset) {
            case 3 -> "Europe/Moscow";
            case 2 -> "Europe/Kiev";  // или Europe/Helsinki
            case 4 -> "Asia/Yerevan"; // или Europe/Samara
            case 1 -> "Europe/Berlin";
            case 0 -> "UTC";
            case -5 -> "America/New_York";
            default -> "UTC"; // fallback для остальных
        };
    }

    /**
     * Валидирует JSON от GigaChat и конвертирует время в UTC для Google Calendar
     * Используется для голосовых сообщений
     */
    public String validateAndConvertJsonForVoice(String jsonText, Integer timezoneOffset) {
        return validateAndConvertJson(jsonText, timezoneOffset);
    }

    /**
     * Валидирует JSON от GigaChat и конвертирует время в UTC для Google Calendar
     */
    private String validateAndConvertJson(String jsonText, Integer timezoneOffset) {
        try {
            JsonObject jsonObject = gson.fromJson(jsonText, JsonObject.class);
            log.info("JSON от GigaChat успешно распарсен");

            // Проверяем обязательные поля
            if (!jsonObject.has("summary") || jsonObject.get("summary").getAsString().trim().isEmpty()) {
                log.warn("JSON не содержит summary или он пустой");
                return null;
            }

            if (!jsonObject.has("start") || !jsonObject.get("start").isJsonObject()) {
                log.warn("JSON не содержит start или он не является объектом");
                return null;
            }

            if (!jsonObject.has("end") || !jsonObject.get("end").isJsonObject()) {
                log.warn("JSON не содержит end или он не является объектом");
                return null;
            }

            // Извлекаем данные времени
            JsonObject startObj = jsonObject.getAsJsonObject("start");
            JsonObject endObj = jsonObject.getAsJsonObject("end");

            String startDateTimeStr = startObj.get("dateTime").getAsString();
            String endDateTimeStr = endObj.get("dateTime").getAsString();
            String timezoneName = startObj.get("timeZone").getAsString();

            log.info("Получено время начала: {} в timezone {}", startDateTimeStr, timezoneName);
            log.info("Получено время окончания: {} в timezone {}", endDateTimeStr, timezoneName);

            // Конвертируем локальное время в UTC для Google Calendar
            ZonedDateTime startLocal = parseLocalDateTime(startDateTimeStr, timezoneName);
            ZonedDateTime endLocal = parseLocalDateTime(endDateTimeStr, timezoneName);

            if (startLocal == null || endLocal == null) {
                log.error("Не удалось распарсить время от GigaChat");
                return null;
            }

            // Конвертируем в UTC
            ZonedDateTime startUtc = startLocal.withZoneSameInstant(ZoneId.of("UTC"));
            ZonedDateTime endUtc = endLocal.withZoneSameInstant(ZoneId.of("UTC"));

            log.info("Конвертировано в UTC - start: {}, end: {}", startUtc, endUtc);

            // Обновляем JSON с UTC временем
            startObj.addProperty("dateTime", startUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
            startObj.addProperty("timeZone", "UTC");

            endObj.addProperty("dateTime", endUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
            endObj.addProperty("timeZone", "UTC");

            // Добавляем поля для Google Calendar, если их нет
            if (!jsonObject.has("description")) {
                jsonObject.addProperty("description", "");
            }

            // Добавляем attendees и reminders
            if (!jsonObject.has("attendees")) {
                jsonObject.add("attendees", new com.google.gson.JsonArray());
            }

            if (!jsonObject.has("reminders")) {
                JsonObject reminders = new JsonObject();
                reminders.addProperty("useDefault", false);
                reminders.add("overrides", new com.google.gson.JsonArray());
                jsonObject.add("reminders", reminders);
            }

            String finalJson = gson.toJson(jsonObject);
            log.info("Финальный JSON для Google Calendar: {}", finalJson);
            return finalJson;

        } catch (Exception e) {
            log.error("Ошибка валидации и конвертации JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Парсит локальное время из строки с учетом часового пояса
     */
    private ZonedDateTime parseLocalDateTime(String dateTimeStr, String timezoneName) {
        try {
            // Если время уже в формате ISO с Z, парсим как UTC
            if (dateTimeStr.endsWith("Z")) {
                return ZonedDateTime.parse(dateTimeStr);
            }

            // Иначе парсим как локальное время в указанном timezone
            LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr);
            ZoneId zoneId = ZoneId.of(timezoneName);
            return ZonedDateTime.of(localDateTime, zoneId);

        } catch (Exception e) {
            log.error("Ошибка парсинга времени {} в timezone {}: {}", dateTimeStr, timezoneName, e.getMessage());
            return null;
        }
    }

}

