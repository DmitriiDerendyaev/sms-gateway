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

            // Если timezoneOffset указан, рассчитываем локальное время пользователя
            ZonedDateTime userLocalTime = (timezoneOffset != null) ?
                    nowUtc.plusHours(timezoneOffset) : nowUtc;

            String currentDateTime = userLocalTime.format(DateTimeFormatter.ISO_INSTANT);
            String currentDate = userLocalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String currentTime = userLocalTime.format(DateTimeFormatter.ofPattern("HH:mm"));

            // Указываем в промпте, что время локальное для пользователя
            String timezoneNote = (timezoneOffset != null) ?
                    " (локальное время пользователя UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset + ")" :
                    " (UTC)";
            
            // Формируем улучшенный промпт для GigaChat
            String systemPrompt = "Ты - помощник для создания событий в Google Calendar. " +
                    "Пользователь отправляет текст с описанием события. " +
                    "Твоя задача - извлечь структурированные данные и вернуть ТОЛЬКО валидный JSON.\n\n" +
                    "ФОРМАТ JSON:\n" +
                    "{\n" +
                    "  \"summary\": \"Название события\",\n" +
                    "  \"description\": \"Описание события (может быть пустым или отсутствовать)\",\n" +
                    "  \"start\": {\n" +
                    "    \"dateTime\": \"2024-12-25T15:30:00Z\",\n" +
                    "    \"timeZone\": \"UTC\"\n" +
                    "  },\n" +
                    "  \"end\": {\n" +
                    "    \"dateTime\": \"2024-12-25T16:30:00Z\",\n" +
                    "    \"timeZone\": \"UTC\"\n" +
                    "  },\n" +
                    "  \"attendees\": [],\n" +
                    "  \"reminders\": {\n" +
                    "    \"useDefault\": false,\n" +
                    "    \"overrides\": []\n" +
                    "  }\n" +
                    "}\n\n" +
                    "КРИТИЧЕСКИ ВАЖНО - ПАРСИНГ ВРЕМЕНИ:\n" +
                    "1. ТЕКУЩАЯ ДАТА И ВРЕМЯ" + timezoneNote + ": " + currentDateTime + "\n" +
                    "   Сегодня: " + currentDate + ", время: " + currentTime + timezoneNote.replace(" (", "").replace(")", "") + "\n\n" +
                    "2. ОТНОСИТЕЛЬНОЕ ВРЕМЯ (от текущего момента):\n" +
                    "   - 'через 1 час' = текущее время + 1 час (СЕГОДНЯ, та же дата)\n" +
                    "   - 'через 2 часа' = текущее время + 2 часа (СЕГОДНЯ, та же дата)\n" +
                    "   - 'через 30 минут' = текущее время + 30 минут (СЕГОДНЯ, та же дата)\n" +
                    "   - 'через 1 день' = текущая дата + 1 день, то же время\n" +
                    "   - 'через неделю' = текущая дата + 7 дней, то же время\n\n" +
                    "3. АБСОЛЮТНОЕ ВРЕМЯ:\n" +
                    "   - 'завтра в 10:00' = завтра в 10:00 UTC\n" +
                    "   - '25 декабря в 15:30' = 25 декабря текущего года в 15:30 UTC\n" +
                    "   - 'в 18:00' = сегодня в 18:00 UTC (если еще не прошло, иначе завтра)\n\n" +
                    "4. ИЗВЛЕЧЕНИЕ SUMMARY И DESCRIPTION:\n" +
                    "   - summary: краткое название события (главное действие)\n" +
                    "   - description: дополнительная информация, детали, инструкции\n" +
                    "   Примеры:\n" +
                    "   - 'Запустить пылесос через 1 час, добавь описание, чтобы было чисто'\n" +
                    "     summary: 'Запустить пылесос'\n" +
                    "     description: 'Чтобы было чисто'\n" +
                    "   - 'Встреча с командой завтра в 10:00, обсудить проект'\n" +
                    "     summary: 'Встреча с командой'\n" +
                    "     description: 'Обсудить проект'\n\n" +
                    "5. ПРАВИЛА ФОРМАТИРОВАНИЯ:\n" +
                    "   - Всегда используй СЕГОДНЯШНЮЮ дату для относительного времени ('через X часов/минут')\n" +
                    "   - Формат dateTime: ISO 8601 БЕЗ наносекунд (YYYY-MM-DDTHH:mm:ssZ)\n" +
                    "   - Пример правильного формата: '2024-12-14T16:31:00Z' (НЕ '2024-12-14T16:31:39.694336432Z')\n" +
                    "   - Если время не указано, используй текущее время + 1 час\n" +
                    "   - Если дата не указана, используй СЕГОДНЯШНЮЮ дату\n" +
                    "   - Продолжительность события по умолчанию - 1 час\n" +
                    "   - Поля attendees и reminders могут быть пустыми массивами\n\n" +
                    "6. ПРИМЕРЫ:\n" +
                    "   Вход: 'Запустить пылесос через 1 час, добавь описание, чтобы было чисто'\n" +
                    "   Выход: {\"summary\":\"Запустить пылесос\",\"description\":\"Чтобы было чисто\",\"start\":{\"dateTime\":\"" +
                    userLocalTime.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) +
                    "\",\"timeZone\":\"UTC\"},\"end\":{\"dateTime\":\"" +
                    userLocalTime.plusHours(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) +
                    "\",\"timeZone\":\"UTC\"},\"attendees\":[],\"reminders\":{\"useDefault\":false,\"overrides\":[]}}\n\n" +
                    "7. ВЕРНИ ТОЛЬКО JSON, без markdown, без комментариев, без дополнительного текста!";

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

            // Валидируем и корректируем JSON
            try {
                JsonObject jsonObject = gson.fromJson(jsonText, JsonObject.class);
                log.info("JSON успешно валидирован");
                
                // Убеждаемся, что есть обязательные поля
                if (!jsonObject.has("summary")) {
                    log.warn("JSON не содержит поле 'summary', добавляем значение по умолчанию");
                    jsonObject.addProperty("summary", "Событие");
                }
                
                // Обрабатываем start
                ZonedDateTime startTime;
                if (!jsonObject.has("start")) {
                    log.warn("JSON не содержит поле 'start', добавляем значение по умолчанию");
                    startTime = ZonedDateTime.now(ZoneId.of("UTC")).plusHours(1);
                    JsonObject start = new JsonObject();
                    start.addProperty("dateTime", startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
                    start.addProperty("timeZone", "UTC");
                    jsonObject.add("start", start);
                } else {
                    JsonObject startObj = jsonObject.getAsJsonObject("start");
                    String startDateTimeStr = startObj.get("dateTime").getAsString();
                    ZonedDateTime parsedStartTime = parseAndCorrectDateTime(startDateTimeStr);
                    
                    // Дополнительная валидация: проверяем, не слишком ли далеко в будущем для относительного времени
                    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
                    
                    // Если исходный текст содержит относительное время ("через X часов/минут"), 
                    // но распарсенное время более чем на 24 часа в будущем - это ошибка
                    if (userText.matches(".*через\\s+\\d+\\s*(час|минут|минуту|часа|часов).*") && 
                        parsedStartTime.isAfter(now.plusDays(1))) {
                        log.warn("Обнаружена ошибка парсинга: относительное время '{}' распарсено как далекое будущее {}. Корректируем.", 
                                userText, parsedStartTime);
                        // Вычисляем правильное время на основе текста
                        startTime = extractRelativeTime(userText, now);
                        if (startTime == null) {
                            startTime = now.plusHours(1); // Fallback
                        }
                    } else {
                        startTime = parsedStartTime;
                    }
                    
                    // Обновляем start с исправленным временем
                    startObj.addProperty("dateTime", startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
                    log.info("Время начала события (после корректировки): {}", startTime);
                }
                
                // Обрабатываем end
                ZonedDateTime endTime;
                if (!jsonObject.has("end")) {
                    log.warn("JSON не содержит поле 'end', добавляем значение по умолчанию (start + 1 час)");
                    endTime = startTime.plusHours(1);
                    JsonObject end = new JsonObject();
                    end.addProperty("dateTime", endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
                    end.addProperty("timeZone", "UTC");
                    jsonObject.add("end", end);
                } else {
                    JsonObject endObj = jsonObject.getAsJsonObject("end");
                    String endDateTimeStr = endObj.get("dateTime").getAsString();
                    endTime = parseAndCorrectDateTime(endDateTimeStr);
                    
                    // Проверяем, что end не раньше start
                    if (endTime.isBefore(startTime) || endTime.isEqual(startTime)) {
                        log.warn("Время окончания раньше или равно времени начала, устанавливаем start + 1 час");
                        endTime = startTime.plusHours(1);
                    }
                    
                    // Обновляем end с исправленным временем
                    endObj.addProperty("dateTime", endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
                    log.info("Время окончания события (после корректировки): {}", endTime);
                }
                
                // Убеждаемся, что есть пустые массивы для attendees и reminders, если их нет
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
                log.info("Финальный JSON после корректировки: {}", finalJson);
                return finalJson;
            } catch (Exception e) {
                log.error("Ошибка валидации JSON: {}", e.getMessage(), e);
                return null;
            }

        } catch (Exception e) {
            log.error("Ошибка при парсинге текста в JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Парсит строку с датой/временем и корректирует её, убирая наносекунды и исправляя ошибки.
     * 
     * @param dateTimeStr Строка с датой/временем в формате ISO 8601
     * @return ZonedDateTime в UTC
     */
    private ZonedDateTime parseAndCorrectDateTime(String dateTimeStr) {
        try {
            // Убираем наносекунды, если они есть
            String cleaned = dateTimeStr;
            if (cleaned.contains(".")) {
                // Убираем наносекунды и все после точки до Z или +
                cleaned = cleaned.replaceAll("\\.\\d+([Z+])", "$1");
                // Если осталась точка без Z, убираем её и добавляем Z
                cleaned = cleaned.replaceAll("\\.\\d*$", "Z");
            }
            
            // Убираем Z в конце, если его нет, но есть формат UTC
            if (!cleaned.endsWith("Z") && !cleaned.contains("+") && cleaned.indexOf("-", 10) == -1) {
                cleaned = cleaned + "Z";
            }
            
            log.debug("Очищенная строка времени: {}", cleaned);
            
            // Парсим с разными форматами
            ZonedDateTime parsed;
            try {
                // Пробуем стандартный ISO формат
                parsed = ZonedDateTime.parse(cleaned);
            } catch (DateTimeParseException e) {
                // Пробуем формат без Z
                try {
                    parsed = LocalDateTime.parse(cleaned.replace("Z", "")).atZone(ZoneId.of("UTC"));
                } catch (DateTimeParseException e2) {
                    // Пробуем формат с пробелом
                    cleaned = cleaned.replace(" ", "T");
                    if (!cleaned.endsWith("Z")) {
                        cleaned = cleaned + "Z";
                    }
                    parsed = ZonedDateTime.parse(cleaned);
                }
            }
            
            // Проверяем корректность распарсенного времени
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
            
            // Проверка 1: Время не должно быть в далеком прошлом
            if (parsed.isBefore(now.minusDays(1))) {
                log.warn("Распарсенное время {} находится в прошлом более чем на 1 день (текущее: {}), возможно ошибка парсинга. Используем текущее время + 1 час", parsed, now);
                return now.plusHours(1);
            }
            
            // Проверка 2: Если время в будущем более чем на 7 дней, но исходный текст содержит относительное время
            // (это может быть ошибка парсинга - например, "через 1 час" интерпретировано как следующий день)
            // Эта проверка будет выполнена на уровне выше, где есть доступ к исходному тексту
            
            // Проверка 3: Если время в прошлом, но не более чем на 1 день - это может быть нормально
            // (например, пользователь хочет создать событие на вчера)
            
            return parsed;
            
        } catch (Exception e) {
            log.error("Ошибка при парсинге времени '{}': {}", dateTimeStr, e.getMessage(), e);
            // В случае ошибки возвращаем текущее время + 1 час
            return ZonedDateTime.now(ZoneId.of("UTC")).plusHours(1);
        }
    }

    /**
     * Извлекает относительное время из текста пользователя.
     * Например: "через 1 час" -> текущее время + 1 час
     * 
     * @param userText Текст пользователя
     * @param baseTime Базовое время (обычно текущее)
     * @return ZonedDateTime или null, если не удалось извлечь
     */
    private ZonedDateTime extractRelativeTime(String userText, ZonedDateTime baseTime) {
        try {
            String text = userText.toLowerCase();
            
            // Паттерны для относительного времени
            // "через 1 час", "через 2 часа", "через 30 минут" и т.д.
            java.util.regex.Pattern hourPattern = java.util.regex.Pattern.compile("через\\s+(\\d+)\\s*(час|часа|часов)");
            java.util.regex.Pattern minutePattern = java.util.regex.Pattern.compile("через\\s+(\\d+)\\s*(минут|минуту|минуты)");
            java.util.regex.Pattern dayPattern = java.util.regex.Pattern.compile("через\\s+(\\d+)\\s*(день|дня|дней)");
            
            java.util.regex.Matcher matcher;
            
            // Проверяем часы
            matcher = hourPattern.matcher(text);
            if (matcher.find()) {
                int hours = Integer.parseInt(matcher.group(1));
                log.debug("Извлечено относительное время: через {} часов", hours);
                return baseTime.plusHours(hours);
            }
            
            // Проверяем минуты
            matcher = minutePattern.matcher(text);
            if (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                log.debug("Извлечено относительное время: через {} минут", minutes);
                return baseTime.plusMinutes(minutes);
            }
            
            // Проверяем дни
            matcher = dayPattern.matcher(text);
            if (matcher.find()) {
                int days = Integer.parseInt(matcher.group(1));
                log.debug("Извлечено относительное время: через {} дней", days);
                return baseTime.plusDays(days);
            }
            
            // Специальные случаи
            if (text.contains("через час") || text.contains("через 1 час")) {
                return baseTime.plusHours(1);
            }
            if (text.contains("через полчаса") || text.contains("через 30 минут")) {
                return baseTime.plusMinutes(30);
            }
            
            return null;
        } catch (Exception e) {
            log.warn("Ошибка при извлечении относительного времени из текста '{}': {}", userText, e.getMessage());
            return null;
        }
    }
}

