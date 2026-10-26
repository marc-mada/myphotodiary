-- Desktop presentation (visual theme) chosen per user (18/10/2026, explicit
-- ask) - in the spirit of the legacy UserConfiguration.style setting.
-- 'dark' is the original look ("Dark Gallery"); others: 'bright',
-- 'titanium', 'green-wilderness' (UserSettingsUpdater checks the value).
-- Portable SQL (HSQLDB/PostgreSQL).
ALTER TABLE app_user ADD COLUMN presentation VARCHAR(40) DEFAULT 'dark' NOT NULL;
