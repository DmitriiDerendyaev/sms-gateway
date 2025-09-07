package ru.derendyaev.SmsGatewayLLM.smsgateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.derendyaev.SmsGatewayLLM.smsgateway.dto.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class SmsGatewayClient {

    private final WebClient webClient;

    private final String username;
    private final String password;

    public SmsGatewayClient(@Qualifier("smsWebClient") WebClient webClient,
                            @Value("${app.values.api.sms-gateway.username}") String username,
                            @Value("${app.values.api.sms-gateway.password}") String password) {
        this.webClient = webClient;
        this.username = username;
        this.password = password;
    }
    private HttpHeaders buildAuthHeaders() {
        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Basic " + encodedAuth);
        return headers;
    }

    /** 1. Отправка SMS */
    public MessageResponse sendMessage(SendMessageRequest request) {
        try {
            return webClient.post()
                    .uri("/message")
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(MessageResponse.class)
                    .log()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при отправке SMS: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 2. Получение статуса */
    public MessageResponse getMessageStatus(String messageId) {
        try {
            return webClient.get()
                    .uri("/message/{id}", messageId)
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .retrieve()
                    .bodyToMono(MessageResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при получении статуса: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 3. Создание вебхука */
    public WebhookResponse createWebhook(WebhookRequest request) {
        try {
            return webClient.post()
                    .uri("/webhooks")
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(WebhookResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при создании вебхука: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 4. Список вебхуков */
    public List<WebhookResponse> getWebhooks() {
        try {
            return webClient.get()
                    .uri("/webhooks")
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .retrieve()
                    .bodyToFlux(WebhookResponse.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при получении списка вебхуков: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 5. Удаление вебхука */
    public void deleteWebhook(String webhookId) {
        try {
            webClient.delete()
                    .uri("/webhooks/{id}", webhookId)
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при удалении вебхука: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 6. Список устройств */
    public List<Device> getDevices() {
        try {
            return webClient.get()
                    .uri("/devices")
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .retrieve()
                    .bodyToFlux(Device.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при получении устройств: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** 7. Удаление устройства */
    public void deleteDevice(String deviceId) {
        try {
            webClient.delete()
                    .uri("/devices/{id}", deviceId)
                    .headers(httpHeaders -> httpHeaders.addAll(buildAuthHeaders()))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка при удалении устройства: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }
}
