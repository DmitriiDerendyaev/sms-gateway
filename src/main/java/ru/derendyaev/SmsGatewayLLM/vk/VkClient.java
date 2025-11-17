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

@Slf4j
@Component
@RequiredArgsConstructor
public class VkClient {

    @Value("${app.values.vk.access-token}")
    private String token;

    @Value("${app.values.vk.group-id}")
    private String groupId;

    private static final String API_URL = "https://api.vk.com/method/messages.send";
    private static final String API_VERSION = "5.236";

    public void sendMessage(Integer userId, String text) {
        try {
            log.info("Отправка сообщения в ВК: userId={}, textLength={}", userId, text != null ? text.length() : 0);
            
            RestTemplate rest = new RestTemplate();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

            params.add("user_id", userId.toString());
            params.add("random_id", String.valueOf(System.nanoTime()));
            params.add("message", text);
            params.add("access_token", token);
            params.add("v", API_VERSION);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            
            String response = rest.postForObject(API_URL, request, String.class);
            
            log.info("Ответ от VK API: {}", response);
            
            // Проверяем наличие ошибок в ответе
            if (response != null && response.contains("\"error\"")) {
                log.error("VK API вернул ошибку: {}", response);
            } else {
                log.info("Сообщение успешно отправлено в ВК пользователю {}", userId);
            }

        } catch (RestClientException e) {
            log.error("Ошибка отправки сообщения в ВК (RestClientException): {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Ошибка отправки сообщения в ВК: {}", e.getMessage(), e);
        }
    }
}
