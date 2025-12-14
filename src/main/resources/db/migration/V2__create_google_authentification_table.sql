-- Создание таблицы google_authentification для хранения данных авторизации Google
CREATE TABLE IF NOT EXISTS google_authentification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    access_token TEXT NOT NULL,
    refresh_token TEXT NOT NULL,
    token_expiry TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_google_auth_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Создание индекса для быстрого поиска по user_id
CREATE INDEX IF NOT EXISTS idx_google_auth_user_id ON google_authentification(user_id);

-- Создание уникального индекса для user_id (один пользователь - одна авторизация)
CREATE UNIQUE INDEX IF NOT EXISTS idx_google_auth_user_id_unique ON google_authentification(user_id);

-- Комментарии к таблице и колонкам
COMMENT ON TABLE google_authentification IS 'Таблица для хранения данных авторизации Google OAuth2 для доступа к Google Calendar';
COMMENT ON COLUMN google_authentification.id IS 'Уникальный идентификатор записи';
COMMENT ON COLUMN google_authentification.user_id IS 'ID пользователя из таблицы users';
COMMENT ON COLUMN google_authentification.access_token IS 'Access token для доступа к Google Calendar API';
COMMENT ON COLUMN google_authentification.refresh_token IS 'Refresh token для обновления access token';
COMMENT ON COLUMN google_authentification.token_expiry IS 'Дата и время истечения access token';
COMMENT ON COLUMN google_authentification.created_at IS 'Дата и время создания записи';
COMMENT ON COLUMN google_authentification.updated_at IS 'Дата и время последнего обновления записи';

