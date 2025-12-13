-- SQL скрипт для создания таблицы payment_codes
-- База данных: PostgreSQL
-- Использование: выполните этот скрипт в вашей базе данных PostgreSQL

-- Создание таблицы payment_codes для хранения платежных кодов
CREATE TABLE IF NOT EXISTS payment_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(4) NOT NULL UNIQUE,
    vk_user_id INTEGER NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP
);

-- Создание индекса для быстрого поиска по коду
CREATE INDEX IF NOT EXISTS idx_payment_codes_code ON payment_codes(code);

-- Создание индекса для поиска неиспользованных кодов
CREATE INDEX IF NOT EXISTS idx_payment_codes_is_used ON payment_codes(is_used);

-- Создание индекса для поиска по vk_user_id
CREATE INDEX IF NOT EXISTS idx_payment_codes_vk_user_id ON payment_codes(vk_user_id);

-- Комментарии к таблице и колонкам
COMMENT ON TABLE payment_codes IS 'Таблица для хранения платежных кодов для покупки токенов';
COMMENT ON COLUMN payment_codes.id IS 'Уникальный идентификатор записи';
COMMENT ON COLUMN payment_codes.code IS '4-символьный уникальный код для оплаты';
COMMENT ON COLUMN payment_codes.vk_user_id IS 'ID пользователя VK, для которого создан код';
COMMENT ON COLUMN payment_codes.is_used IS 'Флаг использования кода (true - использован, false - не использован)';
COMMENT ON COLUMN payment_codes.created_at IS 'Дата и время создания кода';
COMMENT ON COLUMN payment_codes.used_at IS 'Дата и время использования кода (NULL если не использован)';

