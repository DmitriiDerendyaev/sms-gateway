package ru.derendyaev.SmsGatewayLLM.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.derendyaev.SmsGatewayLLM.model.UserEntity;
import ru.derendyaev.SmsGatewayLLM.service.GoogleCalendarService;
import ru.derendyaev.SmsGatewayLLM.service.UserService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Контроллер для обработки Google OAuth 2.0 авторизации.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleCalendarService googleCalendarService;
    private final UserService userService;

    @Value("${app.values.google.client-id:}")
    private String clientId;

    @Value("${app.values.google.client-secret:}")
    private String clientSecret;

    @Value("${app.values.google.redirect-uri:https://derendyaev.ru/oauth/google/callback}")
    private String redirectUri;

    // Хранение state для защиты от CSRF (vkUserId -> state)
    private final Map<Integer, String> oauthStates = new ConcurrentHashMap<>();

    // Хранение vkUserId для callback (state -> vkUserId)
    private final Map<String, Integer> stateToVkUserId = new ConcurrentHashMap<>();

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/calendar";

    /**
     * Инициирует OAuth flow для пользователя VK.
     * 
     * @param vkUserId ID пользователя VK
     * @return Redirect на страницу авторизации Google
     */
    @GetMapping("/oauth/google/authorize")
    public ResponseEntity<String> authorize(@RequestParam Integer vkUserId) {
        log.info("Инициация OAuth для пользователя VK: {}", vkUserId);

        // Проверяем, зарегистрирован ли пользователь
        Optional<UserEntity> userOpt = userService.getByVkId(vkUserId);
        if (userOpt.isEmpty()) {
            log.warn("Пользователь VK {} не найден", vkUserId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Пользователь не найден. Пожалуйста, сначала зарегистрируйтесь через /start");
        }

        // Генерируем state для защиты от CSRF
        String state = UUID.randomUUID().toString();
        oauthStates.put(vkUserId, state);
        stateToVkUserId.put(state, vkUserId);

        // Формируем URL для авторизации
        String authUrl = UriComponentsBuilder.fromUriString(GOOGLE_AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent") // Принудительно запрашиваем refresh token
                .queryParam("state", state)
                .build()
                .toUriString();

        log.info("Перенаправление пользователя {} на Google OAuth: {}", vkUserId, authUrl);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", authUrl)
                .build();
    }

    /**
     * Обрабатывает callback от Google OAuth.
     * 
     * @param code Authorization code от Google
     * @param state State для проверки CSRF
     * @param error Ошибка, если авторизация не удалась
     * @param model Model для Thymeleaf
     * @return HTML страница с результатом
     */
    @GetMapping("/oauth/google/callback")
    public String callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            Model model) {
        
        log.info("OAuth callback: code={}, state={}, error={}", code != null ? "present" : "null", state, error);

        // Проверяем наличие ошибки
        if (error != null) {
            log.error("Ошибка авторизации Google: {}", error);
            model.addAttribute("success", false);
            model.addAttribute("message", "Ошибка авторизации: " + error);
            return "oauth-result";
        }

        // Проверяем наличие кода и state
        if (code == null || state == null) {
            log.error("Отсутствует code или state в callback");
            model.addAttribute("success", false);
            model.addAttribute("message", "Отсутствуют необходимые параметры авторизации");
            return "oauth-result";
        }

        // Проверяем state
        Integer vkUserId = stateToVkUserId.get(state);
        if (vkUserId == null) {
            log.error("Неверный state: {}", state);
            model.addAttribute("success", false);
            model.addAttribute("message", "Неверный параметр state. Попробуйте начать авторизацию заново.");
            return "oauth-result";
        }

        // Проверяем, что state соответствует пользователю
        String expectedState = oauthStates.get(vkUserId);
        if (!state.equals(expectedState)) {
            log.error("State не совпадает для пользователя {}", vkUserId);
            model.addAttribute("success", false);
            model.addAttribute("message", "Ошибка проверки безопасности. Попробуйте начать авторизацию заново.");
            return "oauth-result";
        }

        // Очищаем state
        oauthStates.remove(vkUserId);
        stateToVkUserId.remove(state);

        // Обмениваем код на токены
        try {
            RestTemplate restTemplate = new RestTemplate();
            org.springframework.util.MultiValueMap<String, String> params = new org.springframework.util.LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("code", code);
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", redirectUri);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> request = 
                    new org.springframework.http.HttpEntity<>(params, headers);

            @SuppressWarnings("unchecked")
            org.springframework.http.ResponseEntity<Map<String, Object>> response = 
                    restTemplate.postForEntity(GOOGLE_TOKEN_URL, request, (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Ошибка при обмене кода на токены: {}", response.getStatusCode());
                model.addAttribute("success", false);
                model.addAttribute("message", "Ошибка при получении токенов от Google");
                return "oauth-result";
            }

            Map<String, Object> tokenResponse = response.getBody();
            String accessToken = (String) tokenResponse.get("access_token");
            String refreshToken = (String) tokenResponse.get("refresh_token");
            Integer expiresIn = (Integer) tokenResponse.get("expires_in");

            if (accessToken == null || refreshToken == null || expiresIn == null) {
                log.error("Неполный ответ от Google: {}", tokenResponse);
                model.addAttribute("success", false);
                model.addAttribute("message", "Неполный ответ от Google. Попробуйте еще раз.");
                return "oauth-result";
            }

            // Получаем пользователя
            Optional<UserEntity> userOpt = userService.getByVkId(vkUserId);
            if (userOpt.isEmpty()) {
                log.error("Пользователь VK {} не найден при сохранении токенов", vkUserId);
                model.addAttribute("success", false);
                model.addAttribute("message", "Пользователь не найден");
                return "oauth-result";
            }

            UserEntity user = userOpt.get();

            // Сохраняем токены
            googleCalendarService.saveAuth(user.getId(), accessToken, refreshToken, expiresIn.longValue());

            log.info("Токены успешно сохранены для пользователя VK {}", vkUserId);

            model.addAttribute("success", true);
            model.addAttribute("message", "Авторизация Google успешно завершена! Теперь вы можете создавать напоминания в Google Calendar.");
            return "oauth-result";

        } catch (Exception e) {
            log.error("Ошибка при обработке OAuth callback: {}", e.getMessage(), e);
            model.addAttribute("success", false);
            model.addAttribute("message", "Ошибка при обработке авторизации: " + e.getMessage());
            return "oauth-result";
        }
    }
}

