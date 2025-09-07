package ru.derendyaev.SmsGatewayLLM.gigaChat.models.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GigaToken {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_at")
    private Long expiresAt;
}
