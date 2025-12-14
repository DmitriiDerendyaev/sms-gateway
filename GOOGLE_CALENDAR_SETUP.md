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

### Шаг 3: Настройка Redirect URI

В Google Cloud Console добавьте Authorized redirect URIs:
- **Для продакшена**: `https://derendyaev.ru/oauth/google/callback`

⚠️ **ВАЖНО**: Добавьте ТОЧНО этот URI, иначе авторизация не будет работать

### Шаг 4: Настройка application.yaml

В файле `src/main/resources/application.yaml` уже настроены значения по умолчанию для продакшена:
```yaml
app:
  values:
    google:
      client-id: ${APP__VALUES__GOOGLE__CLIENT__ID:}
      client-secret: ${APP__VALUES__GOOGLE__CLIENT__SECRET:}
      redirect-uri: ${APP__VALUES__GOOGLE__REDIRECT__URI:https://derendyaev.ru/oauth/google/callback}
      server-host: ${APP__VALUES__GOOGLE__SERVER__HOST:derendyaev.ru}
      server-protocol: ${APP__VALUES__GOOGLE__SERVER__PROTOCOL:https}
```

**Ничего менять не нужно** - значения будут браться из переменных окружения при деплое.

📖 **Подробная инструкция**: См. файл `PRODUCTION_SETUP.md`

### Шаг 5: Авторизация пользователей

Система автоматически обрабатывает OAuth flow:

1. **Пользователь нажимает кнопку "Создать напоминание"**
2. **Если не авторизован** - получает ссылку на авторизацию в VK
3. **Переходит по ссылке** - открывается страница авторизации Google
4. **Авторизуется в Google** - выбирает аккаунт и разрешает доступ
5. **Google перенаправляет на callback** - система обменивает код на токены
6. **Токены сохраняются в БД** - автоматически через `GoogleCalendarService.saveAuth()`
7. **Показывается страница успеха** - пользователь может закрыть окно
8. **Пользователь может создавать события** - авторизация завершена

**Endpoints:**
- `/oauth/google/authorize?vkUserId={id}` - инициация авторизации
- `/oauth/google/callback` - обработка callback от Google

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

