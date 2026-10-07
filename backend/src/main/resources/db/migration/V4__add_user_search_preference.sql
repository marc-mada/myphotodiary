-- Search result page size, ported from the legacy UserConfiguration.maxQueryLength
-- (default 10, "Nombre maximum de photos dans les recherches") - a real
-- per-user persisted setting, not a hardcoded constant, since the search UI
-- (ANALYSIS.md §5/§9 point 3, QuerySvr) needs it to size its infinite-scroll
-- pages the same way the legacy UI did.
--
-- Folded onto app_user directly rather than porting the legacy's separate
-- UserConfiguration table: that table also carries slideShowInterval/style/
-- defaultLat/defaultLng/minQueryRating, all belonging to screens not built
-- yet (slideshow, theme, map, search-rating-default) - only the one field
-- this screen actually needs is added now, same "no speculative columns"
-- rule as V2/V3.

ALTER TABLE app_user ADD COLUMN max_query_length INTEGER DEFAULT 10 NOT NULL;
