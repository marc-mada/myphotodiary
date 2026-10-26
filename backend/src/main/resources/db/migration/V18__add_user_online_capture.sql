-- Mobile "Online capture" per-user setting (26/10/2026, explicit ask): when
-- false (the default) the mobile menu hides its Camera and Film entries -
-- only Publish (pick existing files) stays. Portable SQL (HSQLDB/PostgreSQL).
ALTER TABLE app_user ADD COLUMN online_capture BOOLEAN DEFAULT FALSE NOT NULL;
