package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Message {

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    @JsonProperty("role")
    private String role; // роль отправителя (system, user, assistant)

    @JsonProperty("content")
    private String content; // содержание сообщения
}