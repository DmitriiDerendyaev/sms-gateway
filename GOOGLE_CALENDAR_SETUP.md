# Настройка Google Calendar Integration

## Описание

Система позволяет пользователям создавать события в Google Calendar через VK-бота, используя текстовые и голосовые сообщения.

## Компоненты системы

### 1. База данных
- **google_authentification** - хранит данные авторизации Google OAuth2
- **calendar_events** - хранит созданные события

### 2. Основные сервисы
- **GoogleCalendarService** - работа с Google Calendar API
- **EventParserService** - парсинг текста пользователя в JSON для Google Calendar
- **VkWebhookController** - обработка сообщений и состояний пользователей

### 3. Состояния пользователя
- **IDLE** - пользователь не создаёт событие (по умолчанию)
- **CREATING_EVENT** - пользователь создаёт событие
- **WAITING_PHONE** - ожидание номера телефона при регистрации

## Настройка Google OAuth

### Шаг 1: Создание OAuth 2.0 Client ID в Google Cloud Console

1. Перейдите в [Google Cloud Console](https://console.cloud.google.com/)
2. Создайте новый проект или выберите существующий
3. Включите Google Calendar API:
   - Перейдите в "APIs & Services" → "Library"
   - Найдите "Google Calendar API" и включите его
4. Создайте OAuth 2.0 Client ID:
   - Перейдите в "APIs & Services" → "Credentials"
   - Нажмите "Create Credentials" → "OAuth 2.0 Client ID"
   - Выберите тип приложения: "Web application"
   - Добавьте Authorized redirect URIs (если нужен OAuth flow)
   - Сохраните Client ID и Client Secret

### Шаг 2: Настройка application.yaml

Добавьте в `src/main/resources/application.yaml`:

```yaml
app:
  values:
    google:
      client-id: "ваш-client-id.apps.googleusercontent.com"
      client-secret: "ваш-client-secret"
```

### Шаг 3: Авторизация пользователей

Для авторизации пользователей через Google OAuth необходимо:

1. Создать endpoint для OAuth callback (например, `/oauth/google/callback`)
2. Использовать метод `GoogleCalendarService.saveAuth()` для сохранения токенов
3. Обработать OAuth flow согласно документации Google OAuth 2.0

Пример обработки OAuth callback:
```java
@GetMapping("/oauth/google/callback")
public String handleGoogleCallback(@RequestParam String code, @RequestParam Long userId) {
    // Обмен code на access_token и refresh_token
    // Сохранение через googleCalendarService.saveAuth(userId, accessToken, refreshToken, expiresIn)
    return "redirect:/success";
}
```

## Использование

### Создание события через текст

1. Пользователь нажимает кнопку "📅 Создать напоминание"
2. Пользователь отправляет текст с описанием события (например: "Встреча с командой завтра в 10:00")
3. Система парсит текст через GigaChat в JSON-формат
4. Создаётся событие в Google Calendar
5. Событие сохраняется в БД
6. Пользователь получает подтверждение

### Создание события через голос

1. Пользователь нажимает кнопку "📅 Создать напоминание"
2. Пользователь отправляет голосовое сообщение
3. Система транскрибирует голос в текст через GigaChat
4. Дальше процесс идентичен текстовому кейсу

## Формат JSON для события

```json
{
  "summary": "Название события",
  "description": "Описание события",
  "start": {
    "dateTime": "2024-12-25T15:30:00Z",
    "timeZone": "UTC"
  },
  "end": {
    "dateTime": "2024-12-25T16:30:00Z",
    "timeZone": "UTC"
  },
  "attendees": [
    {"email": "user@example.com"}
  ],
  "reminders": {
    "useDefault": false,
    "overrides": [
      {"method": "email", "minutes": 30},
      {"method": "popup", "minutes": 10}
    ]
  }
}
```

## Важные замечания

1. **Токены**: Access token автоматически обновляется при истечении срока действия
2. **Часовые пояса**: Все даты сохраняются в UTC
3. **Дедупликация**: Сообщения обрабатываются с дедупликацией для избежания повторной обработки
4. **Баланс токенов**: Для парсинга текста и транскрипции голоса требуется баланс токенов пользователя

## Логирование

Все действия логируются с уровнем INFO:
- Создание событий
- Обновление токенов
- Ошибки при работе с Google Calendar API
- Состояния пользователей

