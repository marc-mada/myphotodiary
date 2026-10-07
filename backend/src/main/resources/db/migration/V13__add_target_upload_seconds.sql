-- App-wide "target average upload time per file" (26/09/2026, explicit ask)
-- - drives the browser's adaptive pre-upload photo shrinking
-- (frontend/src/upload/adaptiveShrink.js): a photo whose predicted transfer
-- time on the measured connection exceeds this is scaled down to fit it
-- (never below 2400px on its long side). Seconds, default 10. App-wide
-- rather than per user, like max_video_size_bytes (V10): it's a trade-off
-- between upload speed and what the archive keeps, an admin decision.
ALTER TABLE app_settings ADD COLUMN target_upload_seconds INTEGER DEFAULT 10 NOT NULL;
