-- Video support (28/08/2026, explicit ask - "have a talk about a new topic
-- not in the legacy: Video") - the `image` table now holds both photos and
-- videos, discriminated by this column rather than a parallel table, so
-- every existing feature (description, rating, tags, search, RBAC) keeps
-- working unmodified for video too. Every existing row is a photo, hence
-- the NOT NULL DEFAULT rather than a nullable column - there is no
-- "unknown" media type, only IMAGE/VIDEO (see MediaType.java).

ALTER TABLE image ADD COLUMN media_type VARCHAR(10) DEFAULT 'IMAGE' NOT NULL;
