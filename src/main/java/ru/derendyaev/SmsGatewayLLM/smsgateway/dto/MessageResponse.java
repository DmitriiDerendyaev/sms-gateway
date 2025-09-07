package ru.derendyaev.SmsGatewayLLM.smsgateway.dto;

import lombok.Data;

import java.util.List;

@Data
public class MessageResponse {
    public String id;
    public String state;
    public List<Recipient> recipients;
    public boolean isHashed;
    public boolean isEncrypted;

    @Override
    public String toString() {
        return "MessageResponse{" +
                "id='" + id + '\'' +
                ", state='" + state + '\'' +
                ", recipients=" + recipients +
                ", isHashed=" + isHashed +
                ", isEncrypted=" + isEncrypted +
                '}';
    }

    public static class Recipient {
        public String phoneNumber;
        public String state;
    }


}