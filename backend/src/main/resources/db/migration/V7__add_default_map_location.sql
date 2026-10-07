-- Default map center for the sequence-geolocation picker, ported from the
-- legacy UserConfiguration.defaultLat/defaultLng (Map screen, see
-- CLAUDE.md's roadmap point 4) - used to place the marker somewhere
-- reasonable for a sequence that doesn't have a location yet, instead of
-- "null island" (0,0). Same "no speculative columns" rule as V4/V5/V6 -
-- ported now because the Map screen is what actually reads it, not before.
--
-- Default matches UserConfiguration.java's own default (48.8567/2.3508,
-- Paris), not admin.jsp's config form placeholder value ("0") - that form
-- field only ever displays an already-loaded value in practice (populated
-- via the same self-service /json/config endpoint this ports), so its
-- static HTML default was never actually a real default for a new user.

-- Plain DOUBLE, matching V3's Directory.latitude/longitude columns exactly
-- (not "DOUBLE PRECISION") - same portable spelling already used there.
ALTER TABLE app_user ADD COLUMN default_latitude DOUBLE DEFAULT 48.8567 NOT NULL;
ALTER TABLE app_user ADD COLUMN default_longitude DOUBLE DEFAULT 2.3508 NOT NULL;
