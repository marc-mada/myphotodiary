-- Default video size cap lowered from 500MB (V10) to 20MB (explicit ask,
-- 28/08/2026) - a new migration rather than editing V10's own INSERT: V10
-- has already run against the real dev database, and Flyway validates every
-- already-applied migration's checksum against its file on each startup -
-- editing it in place would break that (a checksum mismatch), not just be
-- redundant. Still admin-editable from Configure without a redeploy either
-- way (AppSettingsController) - this only changes the seeded starting point.
--
-- 20971520 = 20 MB.

UPDATE app_settings SET max_video_size_bytes = 20971520 WHERE id = 1;
