-- Per-image GPS coordinates (16/09/2026, explicit ask) - read from EXIF at
-- index/import time (ExifGpsReader), null when a photo carries no GPS tag
-- at all (never a placeholder like 0/0). Plain DOUBLE, nullable, matching
-- Directory.latitude/longitude exactly (V3__create_navigation_schema.sql) -
-- same reasoning: not every photo has a location, so there's no sensible
-- default to fall back on the way app_user.default_latitude/longitude
-- (V7, NOT NULL) has one.
ALTER TABLE image ADD COLUMN latitude DOUBLE;
ALTER TABLE image ADD COLUMN longitude DOUBLE;
