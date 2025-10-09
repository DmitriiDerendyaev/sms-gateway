package ru.derendyaev.SmsGatewayLLM.controller.dto;

import lombok.Data;

// DTO для простого запроса
@Data
public class SimpleChatRequest {
    private String text;
    private int maxTokens;
}
