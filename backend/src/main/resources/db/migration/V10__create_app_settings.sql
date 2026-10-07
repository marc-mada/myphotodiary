-- App-wide (not per-user) settings, first of their kind in this schema -
-- every setting so far (V4/V6/V7/V8) is a column on app_user because it's a
-- genuine personal preference (search page size, slideshow delay, default
-- map center, post-it fade). A video size cap is an operational/disk-
-- protection concern instead: it has to apply uniformly regardless of who
-- is uploading, or a Writer could simply pick their own generous limit -
-- see AppSettings.java. A single-row table (surrogate id fixed at 1, not a
-- key-value store) mirrors how narrowly this project adds fields generally:
-- one setting exists so far, so one column, not a speculative generic
-- settings mechanism for settings that don't exist yet.
--
-- 524288000 = 500 MB, a reasonable personal-gallery default for a single
-- video clip - adjustable from Admin -> Configure without a redeploy.

CREATE TABLE app_settings (
    id                   BIGINT NOT NULL PRIMARY KEY,
    max_video_size_bytes BIGINT NOT NULL
);

INSERT INTO app_settings (id, max_video_size_bytes) VALUES (1, 524288000);
