-- Добавление поля timezone_offset в таблицу users
-- timezone_offset хранит смещение от UTC в часах (целое число от -12 до +14)

ALTER TABLE users
ADD COLUMN IF NOT EXISTS timezone_offset INTEGER,
ADD COLUMN IF NOT EXISTS timezone_set_at TIMESTAMP;

-- Комментарии для документации
COMMENT ON COLUMN users.timezone_offset IS 'Смещение часового пояса пользователя от UTC в часах (-12 до +14)';
COMMENT ON COLUMN users.timezone_set_at IS 'Дата и время установки часового пояса пользователя';
