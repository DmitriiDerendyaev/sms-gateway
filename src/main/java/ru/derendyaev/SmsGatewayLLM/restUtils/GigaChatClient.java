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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
public class GigaChatClient {

    private final WebClient webClientChat;
    private final WebClient webClientToken;

    private volatile GigaToken cachedToken;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectWriter jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();

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
        String requestId = getUUID();
        messageHeaders.put("X-Request-ID", Collections.singletonList(requestId));
        String accessToken = getToken().getAccessToken();
        messageHeaders.setBearerAuth(accessToken);

        // Проверяем, есть ли attachments (запрос с аудио)
        boolean hasAttachments = request.getMessages() != null && 
                request.getMessages().stream()
                        .anyMatch(msg -> msg.getAttachments() != null && !msg.getAttachments().isEmpty());

        if (hasAttachments) {
            // Полное логирование для запросов с аудио
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("🎙️ ЗАПРОС К GIGACHAT С АУДИО (POST /api/v1/chat/completions)");
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("📍 URL: https://gigachat.devices.sberbank.ru/api/v1/chat/completions");
            log.info("📋 ЗАГОЛОВКИ:");
            log.info("   Content-Type: {}", messageHeaders.getContentType());
            log.info("   Accept: {}", messageHeaders.getAccept());
            log.info("   X-Request-ID: {}", requestId);
            log.info("   Authorization: Bearer {}...{}", 
                    accessToken != null && accessToken.length() > 20 
                            ? accessToken.substring(0, 20) 
                            : "null",
                    accessToken != null && accessToken.length() > 20 
                            ? accessToken.substring(accessToken.length() - 10) 
                            : "");
            
            try {
                String requestJson = jsonWriter.writeValueAsString(request);
                log.info("📦 ТЕЛО ЗАПРОСА (JSON):");
                log.info("{}", requestJson);
            } catch (Exception e) {
                log.warn("Не удалось сериализовать запрос в JSON: {}", e.getMessage());
                log.info("📦 ТЕЛО ЗАПРОСА (toString): {}", request);
            }
        } else {
            log.info("Запрос к GigaChat: model={}, messages_count={}", 
                    request.getModel(), request.getMessages() != null ? request.getMessages().size() : 0);
            log.debug("Полный запрос к GigaChat: {}", request);
        }

        try {
            GigaMessageResponse response = webClientChat
                    .post()
                    .uri("/api/v1/chat/completions")
                    .headers(httpHeaders -> httpHeaders.addAll(messageHeaders))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GigaMessageResponse.class)
                    .block();
            
            if (hasAttachments) {
                // Полное логирование ответа для запросов с аудио
                log.info("═══════════════════════════════════════════════════════════════");
                log.info("✅ ОТВЕТ ОТ GIGACHAT (успешно)");
                log.info("═══════════════════════════════════════════════════════════════");
                try {
                    String responseJson = jsonWriter.writeValueAsString(response);
                    log.info("📦 ТЕЛО ОТВЕТА (JSON):");
                    log.info("{}", responseJson);
                } catch (Exception e) {
                    log.warn("Не удалось сериализовать ответ в JSON: {}", e.getMessage());
                    log.info("📦 ТЕЛО ОТВЕТА (toString): {}", response);
                }
                if (response.getUsage() != null) {
                    log.info("💰 ИСПОЛЬЗОВАНО ТОКЕНОВ: {}", response.getUsage().getTotalTokens());
                }
                log.info("═══════════════════════════════════════════════════════════════");
            } else {
                log.info("✅ Успешный ответ от GigaChat");
                if (response.getUsage() != null) {
                    log.debug("Использовано токенов: {}", response.getUsage().getTotalTokens());
                }
            }
            return response;
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            int statusCode = e.getStatusCode().value();
            
            // Используем уже определенную переменную hasAttachments
            if (hasAttachments) {
                // Полное логирование ошибки для запросов с аудио
                log.error("═══════════════════════════════════════════════════════════════");
                log.error("❌ ОШИБКА ОТ GIGACHAT (HTTP {})", statusCode);
                log.error("═══════════════════════════════════════════════════════════════");
                log.error("📍 URL: https://gigachat.devices.sberbank.ru/api/v1/chat/completions");
                log.error("📋 ЗАГОЛОВКИ ЗАПРОСА:");
                log.error("   Content-Type: {}", messageHeaders.getContentType());
                log.error("   Accept: {}", messageHeaders.getAccept());
                log.error("   X-Request-ID: {}", requestId);
                log.error("   Authorization: Bearer {}...{}", 
                        accessToken != null && accessToken.length() > 20 
                                ? accessToken.substring(0, 20) 
                                : "null",
                        accessToken != null && accessToken.length() > 20 
                                ? accessToken.substring(accessToken.length() - 10) 
                                : "");
                try {
                    String requestJson = jsonWriter.writeValueAsString(request);
                    log.error("📦 ТЕЛО ЗАПРОСА (JSON):");
                    log.error("{}", requestJson);
                } catch (Exception ex) {
                    log.error("📦 ТЕЛО ЗАПРОСА (toString): {}", request);
                }
                log.error("📦 ТЕЛО ОТВЕТА (ошибка):");
                log.error("{}", responseBody != null ? responseBody : "(пусто)");
                log.error("═══════════════════════════════════════════════════════════════");
            } else {
                log.error("❌ Ошибка GigaChat: {} - {}", statusCode, responseBody);
            }
            
            // Специальная обработка ошибки 422
            if (statusCode == 422) {
                log.error("⚠️ ОШИБКА 422 (Unprocessable Entity) - проблема валидации запроса");
                if (responseBody != null) {
                    if (responseBody.contains("model") || responseBody.contains("Model")) {
                        log.error("✅ РЕШЕНИЕ: Проверьте название модели. Для работы с аудио используйте 'GigaChat-Pro'");
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
        String requestId = getUUID();
        fileHeaders.put("X-Request-ID", Collections.singletonList(requestId));
        String accessToken = getToken().getAccessToken();
        fileHeaders.setBearerAuth(accessToken);

        // Полное логирование для загрузки файла
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("📤 ЗАГРУЗКА ФАЙЛА В GIGACHAT (POST /api/v1/files)");
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("📍 URL: https://gigachat.devices.sberbank.ru/api/v1/files");
        log.info("📋 ЗАГОЛОВКИ:");
        log.info("   Accept: {}", fileHeaders.getAccept());
        log.info("   X-Request-ID: {}", requestId);
        log.info("   Authorization: Bearer {}...{}", 
                accessToken != null && accessToken.length() > 20 
                        ? accessToken.substring(0, 20) 
                        : "null",
                accessToken != null && accessToken.length() > 20 
                        ? accessToken.substring(accessToken.length() - 10) 
                        : "");
        log.info("   Content-Type: multipart/form-data");
        log.info("📦 ДАННЫЕ ФОРМЫ:");
        log.info("   file: {} ({} bytes, {} МБ)", filename, audioBytes.length, 
                String.format("%.2f", audioBytes.length / (1024.0 * 1024.0)));
        log.info("   purpose: general");

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

            FileUploadResponse response = webClientChat
                    .post()
                    .uri("/api/v1/files")
                    .headers(httpHeaders -> httpHeaders.addAll(fileHeaders))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .bodyToMono(FileUploadResponse.class)
                    .block();
            
            // Полное логирование успешного ответа
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("✅ ОТВЕТ ОТ GIGACHAT (успешно)");
            log.info("═══════════════════════════════════════════════════════════════");
            try {
                String responseJson = jsonWriter.writeValueAsString(response);
                log.info("📦 ТЕЛО ОТВЕТА (JSON):");
                log.info("{}", responseJson);
            } catch (Exception e) {
                log.info("📦 ТЕЛО ОТВЕТА (toString): {}", response);
            }
            log.info("📌 FILE_ID: {}", response.getId());
            log.info("═══════════════════════════════════════════════════════════════");
            
            return response;
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            int statusCode = e.getStatusCode().value();
            
            // Полное логирование ошибки
            log.error("═══════════════════════════════════════════════════════════════");
            log.error("❌ ОШИБКА ПРИ ЗАГРУЗКЕ ФАЙЛА (HTTP {})", statusCode);
            log.error("═══════════════════════════════════════════════════════════════");
            log.error("📍 URL: https://gigachat.devices.sberbank.ru/api/v1/files");
            log.error("📋 ЗАГОЛОВКИ ЗАПРОСА:");
            log.error("   Accept: {}", fileHeaders.getAccept());
            log.error("   X-Request-ID: {}", requestId);
            log.error("   Authorization: Bearer {}...{}", 
                    accessToken != null && accessToken.length() > 20 
                            ? accessToken.substring(0, 20) 
                            : "null",
                    accessToken != null && accessToken.length() > 20 
                            ? accessToken.substring(accessToken.length() - 10) 
                            : "");
            log.error("   Content-Type: multipart/form-data");
            log.error("📦 ДАННЫЕ ФОРМЫ:");
            log.error("   file: {} ({} bytes, {} МБ)", filename, audioBytes.length, 
                    String.format("%.2f", audioBytes.length / (1024.0 * 1024.0)));
            log.error("   purpose: general");
            log.error("📦 ТЕЛО ОТВЕТА (ошибка):");
            log.error("{}", responseBody != null ? responseBody : "(пусто)");
            log.error("═══════════════════════════════════════════════════════════════");
            
            // Анализ ошибок и логирование рекомендаций
            if (statusCode == 400 && responseBody != null) {
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