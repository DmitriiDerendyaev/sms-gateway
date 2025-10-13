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
    private final Map<Long, String> pendingPromo = new ConcurrentHashMap<>();

    @Value("${app.values.bot.username}")
    private String botUsername;

    @Value("${app.values.bot.token}")
    private String botToken;

    @PostConstruct
    public void register() throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(this);
        log.info("✅ Telegram Bot '{}' успешно запущен", botUsername);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String message = update.getMessage().getText().trim();
        Long chatId = update.getMessage().getChatId();
        String username = update.getMessage().getFrom().getUserName();

        // Проверка состояний пользователя
        if (userState.containsKey(chatId)) {
            String state = userState.get(chatId);

            if (state.equals("WAITING_PHONE")) {
                String phone = message;
                String promo = pendingPromo.remove(chatId);
                String result = userService.activatePromoWithPhone(chatId, username, promo, phone);
                sendMessage(chatId, result);
                userState.remove(chatId);
                return;
            }
        }

        // Команда /start
        if (message.equals("/start")) {
            String welcome = """
                    👋 Привет! Добро пожаловать в SMS-Gateway!

                    🔑 Чтобы активировать доступ, получи промокод у администратора:
                    👉 [@dmitrii_derendyaev](https://t.me/dmitrii_derendyaev)

                    🌐 Больше информации: [sms-gateway.derendyaev.ru](https://sms-gateway.derendyaev.ru)

                    После этого используй команду:
                    `/promo <твой_код>`
                    """;
            sendMessage(chatId, welcome);
            return;
        }

        // Команда /promo
        if (message.startsWith("/promo")) {
            String[] parts = message.split(" ");
            if (parts.length < 2) {
                sendMessage(chatId, "Введите промокод в формате: /promo ABCD1234");
                return;
            }

            String promoCode = parts[1];
            boolean valid = userService.checkPromoExists(promoCode);
            if (!valid) {
                sendMessage(chatId, "❌ Промокод не найден или уже использован.");
                return;
            }

            pendingPromo.put(chatId, promoCode);
            userState.put(chatId, "WAITING_PHONE");
            sendMessage(chatId, "📱 Введите номер телефона, который будет закреплён за вашим аккаунтом:");
            return;
        }

        // Команда /gen (генерация промокодов)
        if (message.startsWith("/gen")) {
            String[] parts = message.split(" ");
            if (parts.length < 3) {
                sendMessage(chatId, "Использование: /gen <количество> <токены>");
                return;
            }
            int count = Integer.parseInt(parts[1]);
            int tokens = Integer.parseInt(parts[2]);
            String codes = userService.generatePromoCodes(count, tokens);
            sendMessage(chatId, "🎟️ Сгенерировано промокодов:\n" + codes);
            return;
        }

        sendMessage(chatId, "🤖 Неизвестная команда. Используй /start или /promo <код>");
    }

    private void sendMessage(Long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .disableWebPagePreview(true)
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
