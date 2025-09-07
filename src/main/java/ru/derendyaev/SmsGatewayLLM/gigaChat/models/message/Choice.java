package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Choice {

    @JsonProperty("message")
    private Message message; // сообщение от ассистента

    @JsonProperty("index")
    private int index; // индекс выбора

    @JsonProperty("finish_reason")
    private String finishReason; // причина завершения
}