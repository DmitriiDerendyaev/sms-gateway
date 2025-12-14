-- Создание таблицы calendar_events для хранения созданных событий Google Calendar
CREATE TABLE IF NOT EXISTS calendar_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    google_event_id VARCHAR(255) NOT NULL,
    summary TEXT,
    description TEXT,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    timezone VARCHAR(50) DEFAULT 'UTC',
    attendees TEXT,
    reminders TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_calendar_event_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Создание индекса для быстрого поиска по user_id
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_id ON calendar_events(user_id);

-- Создание индекса для поиска по google_event_id
CREATE INDEX IF NOT EXISTS idx_calendar_events_google_event_id ON calendar_events(google_event_id);

-- Создание индекса для поиска по дате начала события
CREATE INDEX IF NOT EXISTS idx_calendar_events_start_time ON calendar_events(start_time);

-- Комментарии к таблице и колонкам
COMMENT ON TABLE calendar_events IS 'Таблица для хранения созданных событий Google Calendar';
COMMENT ON COLUMN calendar_events.id IS 'Уникальный идентификатор записи';
COMMENT ON COLUMN calendar_events.user_id IS 'ID пользователя из таблицы users';
COMMENT ON COLUMN calendar_events.google_event_id IS 'ID события в Google Calendar';
COMMENT ON COLUMN calendar_events.summary IS 'Название события';
COMMENT ON COLUMN calendar_events.description IS 'Описание события';
COMMENT ON COLUMN calendar_events.start_time IS 'Дата и время начала события';
COMMENT ON COLUMN calendar_events.end_time IS 'Дата и время окончания события';
COMMENT ON COLUMN calendar_events.timezone IS 'Часовой пояс события';
COMMENT ON COLUMN calendar_events.attendees IS 'JSON-строка с массивом участников события';
COMMENT ON COLUMN calendar_events.reminders IS 'JSON-строка с настройками напоминаний';
COMMENT ON COLUMN calendar_events.created_at IS 'Дата и время создания записи';
COMMENT ON COLUMN calendar_events.updated_at IS 'Дата и время последнего обновления записи';

