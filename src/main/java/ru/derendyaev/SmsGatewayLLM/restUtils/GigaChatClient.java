package ru.derendyaev.SmsGatewayLLM.restUtils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.auth.GigaToken;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;

import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
public class GigaChatClient {

    private final WebClient webClientChat;
    private final WebClient webClientToken;

    private volatile GigaToken cachedToken;

    @Value("${app.values.api.giga-chat.auth-key}")
    private String authKey;

    @Value("${app.values.api.giga-chat.chat-settings.scope}")
    private String scope;

    public GigaChatClient(@Qualifier("gigaChatWebClient") WebClient webClientChat,
                          @Qualifier("gigaAuthWebClient") WebClient webClientToken) {
        this.webClientChat = webClientChat;
        this.webClientToken = webClientToken;
    }

    public synchronized GigaToken getToken() {
        if (cachedToken == null || isTokenExpired(cachedToken)) {
            log.info("Получение нового токена");
            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            tokenHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            tokenHeaders.set("Authorization", "Basic " + authKey);
            tokenHeaders.put("RqUID", Collections.singletonList(getUUID()));

            cachedToken = webClientToken
                    .post()
                    .uri("/api/v2/oauth")
                    .headers(httpHeaders -> httpHeaders.addAll(tokenHeaders))
                    .body(BodyInserters.fromFormData("scope", scope))
                    .retrieve()
                    .bodyToMono(GigaToken.class)
                    .block();
        }
        return cachedToken;
    }

    private boolean isTokenExpired(GigaToken token) {
        return token.getExpiresAt() <= System.currentTimeMillis() + 60_000; // 1 минута запаса
    }

    public GigaMessageResponse gigaMessageGenerate(GigaMessageRequest request) {
        HttpHeaders messageHeaders = new HttpHeaders();
        messageHeaders.setContentType(MediaType.APPLICATION_JSON);
        messageHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        messageHeaders.put("X-Request-ID", Collections.singletonList(getUUID()));
        messageHeaders.setBearerAuth(getToken().getAccessToken());

        log.info("Запрос к GigaChat: {}", request);

        try {
            return webClientChat
                    .post()
                    .uri("/api/v1/chat/completions")
                    .headers(httpHeaders -> httpHeaders.addAll(messageHeaders))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GigaMessageResponse.class).log()
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка GigaChat: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private String getUUID() {
        return UUID.randomUUID().toString();
    }
}