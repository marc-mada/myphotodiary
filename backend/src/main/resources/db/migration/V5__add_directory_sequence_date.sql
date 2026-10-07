-- The sequence's own nominal date - faithfully porting the legacy
-- Directory.date (ANALYSIS.md §2), missed in V2/V3: distinct from
-- `creation_date` (when the DB row itself was created), this is the date
-- the sequence represents - set from EXIF or the path itself depending on
-- how the sequence was created (ImportSvr's "Classer par date (EXIF)"),
-- then kept in sync with the latest image date on each (re)index
-- (DirectoryIndexer.updateDirDate). Needed now that upload/import faithfully
-- replicates that legacy behavior rather than approximating it.

ALTER TABLE directory ADD COLUMN sequence_date DATE;
