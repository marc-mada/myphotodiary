-- Slideshow delay, ported from the legacy UserConfiguration.slideShowInterval
-- (default 5 seconds, "Delai de passage des photos"/"Slide show delay") - the
-- play control in ImageViewer used a hardcoded 5000ms constant until now (a
-- documented gap since the EXIF/import work); this makes it a real per-user
-- setting instead, same "no speculative columns" rule as V4/V5 - only the
-- field the gallery viewer actually reads is added here.
--
-- V4's note about not porting the legacy UserConfiguration table wholesale
-- still applies to the remaining fields (style/defaultLat/defaultLng/
-- minQueryRating) - those still belong to screens not built yet.

ALTER TABLE app_user ADD COLUMN slide_show_interval INTEGER DEFAULT 5 NOT NULL;
