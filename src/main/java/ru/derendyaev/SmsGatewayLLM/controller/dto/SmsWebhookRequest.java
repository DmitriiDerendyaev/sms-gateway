package ru.derendyaev.SmsGatewayLLM.controller.dto;

import lombok.Data;
import lombok.Getter;

@Getter
public class SmsWebhookRequest {
    private String deviceId;
    private String event;
    private String id;
    private Payload payload;
    private String webhookId;

    @Getter
    public static class Payload {
        private String message;
        private String receivedAt;
        private String messageId;
        private String phoneNumber;
        private int simNumber;

        @Override
        public String toString() {
            return "Payload{" +
                    "message='" + message + '\'' +
                    ", receivedAt='" + receivedAt + '\'' +
                    ", messageId='" + messageId + '\'' +
                    ", phoneNumber='" + phoneNumber + '\'' +
                    ", simNumber=" + simNumber +
                    '}';
        }
    }
}
