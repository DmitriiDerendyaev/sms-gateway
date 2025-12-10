package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class Message {

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public Message(String role, String content, List<String> attachments) {
        this.role = role;
        this.content = content;
        this.attachments = attachments;
    }

    @JsonProperty("role")
    private String role; // роль отправителя (system, user, assistant)

    @JsonProperty("content")
    private String content; // содержание сообщения

    @JsonProperty("attachments")
    private List<String> attachments; // список file_id для вложений
}