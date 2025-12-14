package ru.derendyaev.SmsGatewayLLM.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class VkClient {

    @Value("${app.values.vk.access-token}")
    private String token;

    @Value("${app.values.vk.group-id}")
    private String groupId;

    // Согласно документации: https://dev.vk.com/ru/api/api-requests
    // URL должен быть api.vk.ru (не api.vk.com)
    private static final String API_URL = "https://api.vk.ru/method/messages.send";
    // Актуальная версия API: 5.199
    private static final String API_VERSION = "5.199";
    
    private final Random random = new Random();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendMessage(Integer userId, String text) {
        sendMessage(userId, text, null);
    }

    /**
     * Отправляет сообщение с опциональной клавиатурой.
     * 
     * @param userId ID пользователя
     * @param text Текст сообщения
     * @param keyboardJson JSON-строка с клавиатурой (null, если клавиатура не нужна)
     */
    public void sendMessage(Integer userId, String text, String keyboardJson) {
        String responseBody = null;
        try {
            log.info("Отправка сообщения в ВК: userId={}, textLength={}, hasKeyboard={}", 
                    userId, text != null ? text.length() : 0, keyboardJson != null);
            
            RestTemplate rest = new RestTemplate();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

            // Для сообществ можно использовать peer_id вместо user_id
            // peer_id = user_id для личных сообщений
            params.add("peer_id", userId.toString());
            
            // random_id должен быть уникальным int32 (32-битное целое число)
            // Используем комбинацию timestamp (младшие биты) и случайного числа
            // Это гарантирует уникальность и не вызывает переполнения int32
            int timestampPart = (int) (System.currentTimeMillis() & 0xFFFFF); // младшие 20 бит
            int randomPart = random.nextInt(10000); // случайное число 0-9999
            int randomId = timestampPart * 10000 + randomPart;
            params.add("random_id", String.valueOf(randomId));
            
            params.add("message", text);
            
            // Добавляем клавиатуру, если она указана
            if (keyboardJson != null && !keyboardJson.isEmpty()) {
                params.add("keyboard", keyboardJson);
            }
            
            params.add("access_token", token);
            params.add("v", API_VERSION);

            HttpHeaders headers = new HttpHeaders();
            // Согласно документации: для POST-запросов нужен Content-Type: application/x-www-form-urlencoded
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            
            log.debug("Отправка запроса к VK API: URL={}, peerId={}, randomId={}", API_URL, userId, randomId);
            
            ResponseEntity<String> response = rest.postForEntity(API_URL, request, String.class);
            responseBody = response.getBody();
            
            // Всегда логируем полный ответ для отладки
            log.info("=== Ответ от VK API ===");
            log.info("HTTP Status: {}", response.getStatusCode());
            log.info("Response Body: {}", responseBody);
            log.info("======================");
            
            // Парсим и анализируем ответ
            if (responseBody != null) {
                try {
                    JsonNode jsonResponse = objectMapper.readTree(responseBody);
                    
                    // Проверяем наличие ошибки
                    if (jsonResponse.has("error")) {
                        JsonNode error = jsonResponse.get("error");
                        int errorCode = error.has("error_code") ? error.get("error_code").asInt() : -1;
                        String errorMsg = error.has("error_msg") ? error.get("error_msg").asText() : "Unknown error";
                        
                        log.error("❌ VK API вернул ошибку:");
                        log.error("   Код ошибки: {}", errorCode);
                        log.error("   Сообщение: {}", errorMsg);
                        log.error("   Полный ответ: {}", responseBody);
                        
                        // Детальная обработка известных ошибок
                        switch (errorCode) {
                            case 5:
                                log.error("   ⚠️ Ошибка авторизации: неверный access_token.");
                                log.error("   📝 Инструкция по получению токена:");
                                log.error("      1. Перейдите в настройки вашей группы ВК");
                                log.error("      2. Управление → Работа с API → Ключи доступа");
                                log.error("      3. Создайте новый ключ с правами: messages");
                                log.error("      4. Скопируйте полный токен в application.yaml");
                                break;
                            case 6:
                                log.error("   ⚠️ Превышен лимит запросов (20 запросов/сек для сообществ).");
                                log.error("   💡 Подождите перед следующей отправкой.");
                                break;
                            case 7:
                                log.error("   ⚠️ Нет прав для выполнения операции.");
                                log.error("   📝 Проверьте права токена в настройках группы.");
                                break;
                            case 10:
                                log.error("   ⚠️ Внутренняя ошибка сервера VK.");
                                break;
                            case 113:
                                log.error("   ⚠️ Неверный идентификатор пользователя.");
                                break;
                            default:
                                log.error("   ⚠️ Неизвестная ошибка. Код: {}", errorCode);
                        }
                    } else if (jsonResponse.has("response")) {
                        log.info("✅ Сообщение успешно отправлено в ВК пользователю {}", userId);
                        JsonNode responseNode = jsonResponse.get("response");
                        log.debug("   Response data: {}", responseNode);
                    } else {
                        log.warn("⚠️ Неожиданный формат ответа от VK API: {}", responseBody);
                    }
                } catch (Exception e) {
                    log.error("Ошибка при парсинге JSON ответа: {}", e.getMessage());
                    log.error("Сырой ответ: {}", responseBody);
                }
            } else {
                log.warn("⚠️ Получен пустой ответ от VK API");
            }

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            responseBody = e.getResponseBodyAsString();
            log.error("❌ HTTP ошибка при отправке сообщения в ВК:");
            log.error("   HTTP Status: {}", e.getStatusCode());
            log.error("   Status Text: {}", e.getStatusText());
            log.error("   Response Body: {}", responseBody);
            log.error("   Headers: {}", e.getResponseHeaders());
            log.error("   Exception: ", e);
        } catch (RestClientException e) {
            log.error("❌ Ошибка отправки сообщения в ВК (RestClientException):");
            log.error("   Message: {}", e.getMessage());
            log.error("   Response Body (если есть): {}", responseBody);
            log.error("   Exception: ", e);
        } catch (Exception e) {
            log.error("❌ Неожиданная ошибка при отправке сообщения в ВК:");
            log.error("   Message: {}", e.getMessage());
            log.error("   Response Body (если есть): {}", responseBody);
            log.error("   Exception: ", e);
        }
    }
}
