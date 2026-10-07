-- User/group/role schema for the new Admin screen (myphotodiary migration,
-- ANALYSIS.md §9 point 1). Not a copy of the legacy schema: this is a fresh
-- schema for the new stack, run against its own isolated dev database
-- (CLAUDE.md) - avoids "user"/"group" as table names too, both reserved
-- words that caused real schema-qualification friction in the legacy app
-- (see ANALYSIS.md §2 on hibernate.default_schema).
--
-- Written in portable SQL (CLAUDE.md decision #4) so it runs unmodified on
-- both HSQLDB (phase 1) and PostgreSQL 10+ (phase 2) - no HSQLDB- or
-- Postgres-specific syntax, natural (not surrogate) primary keys here so no
-- IDENTITY columns are needed in this particular migration.

CREATE TABLE app_group (
    group_name    VARCHAR(255) NOT NULL PRIMARY KEY,
    description   VARCHAR(1000),
    creation_date DATE NOT NULL
);

CREATE TABLE app_user (
    user_name     VARCHAR(255) NOT NULL PRIMARY KEY,
    long_name     VARCHAR(255),
    password      VARCHAR(255) NOT NULL,
    creation_date DATE NOT NULL
);

CREATE TABLE role_assignment (
    user_name  VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    is_primary BOOLEAN      NOT NULL,
    PRIMARY KEY (user_name, group_name),
    FOREIGN KEY (user_name) REFERENCES app_user (user_name) ON DELETE CASCADE,
    FOREIGN KEY (group_name) REFERENCES app_group (group_name) ON DELETE CASCADE
);
