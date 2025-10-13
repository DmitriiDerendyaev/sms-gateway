package ru.derendyaev.SmsGatewayLLM.bot;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.derendyaev.SmsGatewayLLM.service.UserService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmsGatewayBot extends TelegramLongPollingBot {

    private final UserService userService;

    private final Map<Long, String> userState = new ConcurrentHashMap<>();

    @Value("${app.values.bot.username}")
    private String botUsername;

    @Value("${app.values.bot.token}")
    private String botToken;

    @PostConstruct
    public void register() throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(this);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String message = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();

            if (message.startsWith("/gen")) {
                String[] parts = message.split(" ");
                int count = Integer.parseInt(parts[1]);
                int tokens = Integer.parseInt(parts[2]);
                String codes = userService.generatePromoCodes(count, tokens);
                sendMessage(chatId, "🎟️ Сгенерировано промокодов:\n" + codes);
                return;
            }

            if (message.startsWith("/promo")) {
                String[] parts = message.split(" ");
                if (parts.length < 2) {
                    sendMessage(chatId, "Введите промокод: /promo ABCD1234");
                    return;
                }
                String code = parts[1];
                String result = userService.activatePromo(chatId, code);
                sendMessage(chatId, result);
                return;
            }

            sendMessage(chatId, "Привет! Используй /promo <код> для активации токенов.");
        }
    }

    private void sendMessage(Long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (Exception e) {
            log.error("Ошибка при отправке сообщения пользователю {}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
}
