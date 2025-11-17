package ru.derendyaev.SmsGatewayLLM.vk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
            RestTemplate rest = new RestTemplate();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

            params.add("user_id", userId.toString());
            params.add("random_id", String.valueOf(System.nanoTime()));
            params.add("message", text);
            params.add("access_token", token);
            params.add("v", API_VERSION);

            rest.postForObject(API_URL, params, String.class);

        } catch (Exception e) {
            log.error("Ошибка отправки сообщения в ВК: {}", e.getMessage());
        }
    }
}
