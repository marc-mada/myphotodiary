-- Video sharing links (14/10/2026, explicit ask): whether share links
-- (picture, sequence, search result) may include videos, streamed to
-- visitors without an account. Off by default - turned on from
-- Admin -> Configure -> App-wide settings. Portable SQL (HSQLDB/PostgreSQL).
ALTER TABLE app_settings ADD COLUMN allow_video_sharing BOOLEAN DEFAULT FALSE NOT NULL;
