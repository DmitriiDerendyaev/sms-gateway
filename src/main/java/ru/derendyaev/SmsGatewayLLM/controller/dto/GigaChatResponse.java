package ru.derendyaev.SmsGatewayLLM.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GigaChatResponse {
    private String content;
    private int usedTokens;
}
