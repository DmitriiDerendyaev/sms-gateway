package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class GigaMessageRequest {

    public GigaMessageRequest(String model, boolean stream, int updateInterval, List<Message> messages, int n, int maxTokens, double repetitionPenalty) {
        this.model = model;
        this.stream = stream;
        this.updateInterval = updateInterval;
        this.messages = messages;
        this.n = n;
        this.maxTokens = maxTokens;
        this.repetitionPenalty = repetitionPenalty;
    }

    @JsonProperty("model")
    private String model;

    @JsonProperty("stream")
    private boolean stream;

    @JsonProperty("update_interval")
    private int updateInterval;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("n")
    private int n;

    @JsonProperty("max_tokens")
    private int maxTokens;

    @JsonProperty("repetition_penalty")
    private double repetitionPenalty;
}