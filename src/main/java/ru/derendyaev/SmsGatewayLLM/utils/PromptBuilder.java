package ru.derendyaev.SmsGatewayLLM.utils;


import org.springframework.stereotype.Component;
import ru.derendyaev.SmsGatewayLLM.gigaChat.PromptConstants;
import ru.derendyaev.SmsGatewayLLM.gigaChat.models.message.Message;

import java.util.List;

import static ru.derendyaev.SmsGatewayLLM.gigaChat.models.GigaChatConstant.SYSTEM_ROLE;
import static ru.derendyaev.SmsGatewayLLM.gigaChat.models.GigaChatConstant.USER_ROLE;

@Component
public class PromptBuilder {

    public List<Message> buildMessages(String message) {
        String systemPrompt = PromptConstants.SYSTEM_PROMPT;
        String userPrompt = message;

        return List.of(
                new Message(SYSTEM_ROLE, systemPrompt),
                new Message(USER_ROLE, userPrompt)
        );
    }
}