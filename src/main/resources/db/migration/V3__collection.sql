CREATE TABLE brief_news_snapshot (cutoff TIMESTAMPTZ PRIMARY KEY);
CREATE TABLE brief_news (
 cutoff TIMESTAMPTZ NOT NULL REFERENCES brief_news_snapshot(cutoff),
 rank INTEGER NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
 PRIMARY KEY (cutoff, rank)
);
CREATE TABLE brief_collection_repo (
 project TEXT PRIMARY KEY, eligible_floor TIMESTAMPTZ NOT NULL, completed_cutoff TIMESTAMPTZ
);
CREATE TABLE brief_release (
 id BIGINT PRIMARY KEY, project TEXT NOT NULL, version TEXT NOT NULL, url TEXT NOT NULL,
 published_at TIMESTAMPTZ NOT NULL, summary TEXT, notes TEXT
);
CREATE INDEX brief_release_published_idx ON brief_release(project, published_at);
