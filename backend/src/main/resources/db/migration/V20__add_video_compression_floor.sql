-- Video compression in the browser before upload (28/10/2026, explicit ask):
-- the lowest resolution a compressed video may be reduced to, on its short
-- side - 720 (default, explicit ask) or 1080. Read by the browser only
-- (GET /api/app-settings), like target_upload_seconds (V13).
-- Portable SQL (HSQLDB/PostgreSQL).
ALTER TABLE app_settings ADD COLUMN video_compression_floor INTEGER DEFAULT 720 NOT NULL;
