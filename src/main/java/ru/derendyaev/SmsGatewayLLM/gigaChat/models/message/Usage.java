package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Usage {

    @JsonProperty("prompt_tokens")
    private int promptTokens; // количество токенов в запросе

    @JsonProperty("completion_tokens")
    private int completionTokens; // количество токенов в ответе

    @JsonProperty("total_tokens")
    private int totalTokens; // общее количество токенов
}