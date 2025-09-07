package ru.derendyaev.SmsGatewayLLM.smsgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Builder
@AllArgsConstructor
public class SendMessageRequest {
    public TextMessage textMessage;
    public List<String> phoneNumbers;
    public Boolean withDeliveryReport;
    public Integer ttl;
    public Integer simNumber;
    public Integer priority;
    public Boolean isEncrypted;

    public static class TextMessage {
        public String text;
    }
}