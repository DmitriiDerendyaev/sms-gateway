package ru.derendyaev.SmsGatewayLLM.restUtils;

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
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.file.FileUploadResponse;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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

        log.info("Запрос к GigaChat: model={}, messages_count={}", 
                request.getModel(), request.getMessages() != null ? request.getMessages().size() : 0);
        log.debug("Полный запрос к GigaChat: {}", request);

        try {
            GigaMessageResponse response = webClientChat
                    .post()
                    .uri("/api/v1/chat/completions")
                    .headers(httpHeaders -> httpHeaders.addAll(messageHeaders))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GigaMessageResponse.class)
                    .block();
            
            log.info("✅ Успешный ответ от GigaChat");
            if (response.getUsage() != null) {
                log.debug("Использовано токенов: {}", response.getUsage().getTotalTokens());
            }
            return response;
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            int statusCode = e.getStatusCode().value();
            log.error("❌ Ошибка GigaChat: {} - {}", statusCode, responseBody);
            
            // Специальная обработка ошибки 422
            if (statusCode == 422) {
                log.error("⚠️ ОШИБКА 422 (Unprocessable Entity) - проблема валидации запроса");
                if (responseBody != null) {
                    if (responseBody.contains("model") || responseBody.contains("Model")) {
                        log.error("✅ РЕШЕНИЕ: Проверьте название модели. Для работы с аудио используйте 'GigaChat-preview'");
                    }
                    if (responseBody.contains("attachments") || responseBody.contains("file")) {
                        log.error("✅ РЕШЕНИЕ: Проверьте структуру attachments. Должен быть массив строк с file_id");
                    }
                    if (responseBody.contains("size") || responseBody.contains("limit")) {
                        log.error("✅ РЕШЕНИЕ: Превышен размер файла или контекста. Максимум аудио: 35 МБ");
                    }
                    if (responseBody.contains("context") || responseBody.contains("window")) {
                        log.error("✅ РЕШЕНИЕ: Превышен размер контекста модели. Попробуйте более короткое аудио");
                    }
                }
            }
            
            throw e;
        }
    }

    /**
     * Загружает файл в хранилище GigaChat через API файлов
     * Файл будет доступен для использования в запросах на генерацию ответов
     * @param audioBytes байты аудиофайла
     * @param filename имя файла (например, "audio.mp3")
     * @return FileUploadResponse с file_id
     */
    public FileUploadResponse uploadFile(byte[] audioBytes, String filename) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        fileHeaders.put("X-Request-ID", Collections.singletonList(getUUID()));
        fileHeaders.setBearerAuth(getToken().getAccessToken());

        log.info("Загрузка файла в GigaChat: filename={}, size={} bytes", filename, audioBytes.length);

        try {
            // Создаем multipart/form-data
            Resource resource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", resource);
            parts.add("purpose", "general"); // Обязательный параметр: позволяет использовать файл в запросах на генерацию ответов

            return webClientChat
                    .post()
                    .uri("/api/v1/files")
                    .headers(httpHeaders -> httpHeaders.addAll(fileHeaders))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .bodyToMono(FileUploadResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Ошибка при загрузке файла в GigaChat: {} - {}", e.getStatusCode(), responseBody);
            
            // Анализ ошибок и логирование рекомендаций
            if (e.getStatusCode().value() == 400 && responseBody != null) {
                if (responseBody.contains("Purpose is not supported") || responseBody.contains("No purpose provided")) {
                    log.error("❌ ОШИБКА: Неправильный или отсутствующий параметр 'purpose'");
                    log.error("✅ РЕШЕНИЕ: Используйте purpose='general' для загрузки файлов в хранилище");
                }
                if (responseBody.contains("file size") || responseBody.contains("size limit")) {
                    log.error("❌ ОШИБКА: Превышен размер файла");
                    log.error("✅ РЕШЕНИЕ: Максимальный размер аудиофайла - 35 МБ");
                }
            }
            
            throw e;
        }
    }

    private String getUUID() {
        return UUID.randomUUID().toString();
    }
}