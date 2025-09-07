package ru.derendyaev.SmsGatewayLLM.gigaChat.models.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GigaMessageResponse {

    @JsonProperty("choices")
    private List<Choice> choices; // список вариантов ответов

    @JsonProperty("created")
    private long created; // время создания

    @JsonProperty("ru/derendyaev/ideathesis_topic_service/model")
    private String model; // идентификатор модели

    @JsonProperty("object")
    private String object; // тип объекта

    @JsonProperty("usage")
    private Usage usage; // информация о токенах

    @Override
    public String toString() {
        return choices.get(0).getMessage().getContent();
    }
}
