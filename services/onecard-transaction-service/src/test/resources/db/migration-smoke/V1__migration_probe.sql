CREATE TABLE migration_probe (
    id BIGINT NOT NULL PRIMARY KEY,
    probe_value VARCHAR(32) NOT NULL
);

INSERT INTO migration_probe (id, probe_value) VALUES (1, 'migrated');
