package ru.derendyaev.SmsGatewayLLM.controller;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.derendyaev.SmsGatewayLLM.controller.dto.SimpleChatRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.PromptConstants;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageRequest;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.GigaMessageResponse;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.Message;
import ru.derendyaev.SmsGatewayLLM.restUtils.GigaChatClient;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/gigachat")
@RequiredArgsConstructor
public class GigaChatController {

    private final GigaChatClient gigaChatClient;

    /**
     * POST /api/gigachat/message
     * Отправка запроса к GigaChat.
     * Возвращает содержимое ответа и количество использованных токенов.
     */
    @PostMapping("/message")
    public ResponseEntity<GigaChatResponse> sendMessage(@RequestBody GigaMessageRequest request) {
        log.info("Получен запрос от клиента: {}", request);

        try {
            GigaMessageResponse gigaResponse = gigaChatClient.gigaMessageGenerate(request);

            if (gigaResponse == null || gigaResponse.getChoices() == null || gigaResponse.getChoices().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new GigaChatResponse("Пустой ответ от модели", 0));
            }

            String content = gigaResponse.getChoices().get(0).getMessage().getContent();
            int usedTokens = 0;

            // если API возвращает usage — можно достать количество токенов
            if (gigaResponse.getUsage() != null) {
                usedTokens = gigaResponse.getUsage().getTotalTokens();
            }

            GigaChatResponse response = new GigaChatResponse(content, usedTokens);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Ошибка при обращении к GigaChat: ", e);
            return ResponseEntity.internalServerError()
                    .body(new GigaChatResponse("Ошибка при обращении к GigaChat: " + e.getMessage(), 0));
        }
    }

    /**
     * POST /api/gigachat/simple
     * Упрощённый запрос — только текст и максимальное количество токенов
     */
    @PostMapping("/simple")
    public ResponseEntity<GigaChatResponse> simpleMessage(@RequestBody SimpleChatRequest simpleRequest) {
        log.info("Простой запрос: {}", simpleRequest);

        GigaMessageRequest request = new GigaMessageRequest(
                "GigaChat:latest",
                false,
                0,
                List.of(
                        new Message("system", PromptConstants.DEFAULT_SYSTEM_PROMPT),
                        new Message("user", simpleRequest.getText())
                ),
                1,
                simpleRequest.getMaxTokens(),
                1.0
        );

        try {
            GigaMessageResponse gigaResponse = gigaChatClient.gigaMessageGenerate(request);

            if (gigaResponse == null || gigaResponse.getChoices() == null || gigaResponse.getChoices().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new GigaChatResponse("Пустой ответ от модели", 0));
            }

            String content = gigaResponse.getChoices().get(0).getMessage().getContent();
            int usedTokens = 0;
            if (gigaResponse.getUsage() != null) {
                usedTokens = gigaResponse.getUsage().getTotalTokens();
            }

            return ResponseEntity.ok(new GigaChatResponse(content, usedTokens));

        } catch (Exception e) {
            log.error("Ошибка при обращении к GigaChat (simple): ", e);
            return ResponseEntity.internalServerError()
                    .body(new GigaChatResponse("Ошибка при обращении к GigaChat: " + e.getMessage(), 0));
        }
    }

    /**
     * GET /api/gigachat/system-prompt
     * Возвращает текущий системный промпт, используемый по умолчанию.
     */
    @GetMapping("/system-prompt")
    public ResponseEntity<String> getSystemPrompt() {
        return ResponseEntity.ok(PromptConstants.DEFAULT_SYSTEM_PROMPT);
    }

    // DTO для ответа клиенту
    @Data
    @AllArgsConstructor
    public static class GigaChatResponse {
        private String content;
        private int usedTokens;
    }
}