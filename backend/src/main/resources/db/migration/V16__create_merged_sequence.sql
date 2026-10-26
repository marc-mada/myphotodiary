-- Share links of a merged sequence (17/10/2026, explicit ask): when
-- sequence A is merged into B (rename onto an existing sequence), A's row
-- disappears, so links shared for A used to stop working. This remembers,
-- per merged-away sequence id, which pictures came from it (and its own
-- comment), so an old link keeps showing exactly those pictures - never
-- B's other ones. Removed with B (or a picture with its row).
-- Portable SQL (HSQLDB/PostgreSQL).
CREATE TABLE merged_sequence (
    old_directory_id    BIGINT        PRIMARY KEY,
    target_directory_id BIGINT        NOT NULL REFERENCES directory (id) ON DELETE CASCADE,
    description         VARCHAR(4000)
);

CREATE TABLE merged_sequence_image (
    old_directory_id BIGINT NOT NULL REFERENCES merged_sequence (old_directory_id) ON DELETE CASCADE,
    image_id         BIGINT NOT NULL REFERENCES image (id) ON DELETE CASCADE,
    PRIMARY KEY (old_directory_id, image_id)
);
