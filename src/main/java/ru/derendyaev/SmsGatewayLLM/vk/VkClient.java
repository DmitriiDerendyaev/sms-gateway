package ru.derendyaev.SmsGatewayLLM.vk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

    public void sendMessage(Integer userId, String text) {
        try {
            log.info("Отправка сообщения в ВК: userId={}, textLength={}", userId, text != null ? text.length() : 0);
            
            RestTemplate rest = new RestTemplate();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

            params.add("user_id", userId.toString());
            
            // random_id должен быть уникальным int32 (32-битное целое число)
            // Используем комбинацию timestamp (младшие биты) и случайного числа
            // Это гарантирует уникальность и не вызывает переполнения int32
            int timestampPart = (int) (System.currentTimeMillis() & 0xFFFFF); // младшие 20 бит
            int randomPart = random.nextInt(10000); // случайное число 0-9999
            int randomId = timestampPart * 10000 + randomPart;
            params.add("random_id", String.valueOf(randomId));
            
            params.add("message", text);
            params.add("access_token", token);
            params.add("v", API_VERSION);

            HttpHeaders headers = new HttpHeaders();
            // Согласно документации: для POST-запросов нужен Content-Type: application/x-www-form-urlencoded
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            // Альтернативный вариант: можно использовать заголовок Authorization вместо параметра access_token
            // headers.set("Authorization", "Bearer " + token);
            
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            
            log.debug("Отправка запроса к VK API: URL={}, userId={}, randomId={}", API_URL, userId, randomId);
            String response = rest.postForObject(API_URL, request, String.class);
            
            log.info("Ответ от VK API: {}", response);
            
            // Проверяем наличие ошибок в ответе
            if (response != null && response.contains("\"error\"")) {
                log.error("VK API вернул ошибку: {}", response);
                
                // Парсим код ошибки для более информативного сообщения
                if (response.contains("\"error_code\":5")) {
                    log.error("Ошибка авторизации: неверный access_token. Проверьте токен в конфигурации.");
                } else if (response.contains("\"error_code\":6")) {
                    log.error("Превышен лимит запросов. Подождите перед следующей отправкой.");
                }
            } else if (response != null && response.contains("\"response\"")) {
                log.info("Сообщение успешно отправлено в ВК пользователю {}", userId);
            } else {
                log.warn("Неожиданный формат ответа от VK API: {}", response);
            }

        } catch (RestClientException e) {
            log.error("Ошибка отправки сообщения в ВК (RestClientException): {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Ошибка отправки сообщения в ВК: {}", e.getMessage(), e);
        }
    }
}
