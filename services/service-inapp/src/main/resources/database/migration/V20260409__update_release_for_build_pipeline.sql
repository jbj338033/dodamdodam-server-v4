ALTER TABLE app_releases RENAME COLUMN release_url TO repository_url;

ALTER TABLE app_releases ADD COLUMN ref VARCHAR(255) NOT NULL DEFAULT 'main';

ALTER TABLE app_releases ADD COLUMN build_log TEXT NULL;

UPDATE app_releases
SET repository_url = SUBSTRING_INDEX(repository_url, '/releases/tag/', 1)
WHERE repository_url LIKE '%/releases/tag/%';

UPDATE app_releases SET status = 'BUILD_FAILED' WHERE status = 'PENDING';
