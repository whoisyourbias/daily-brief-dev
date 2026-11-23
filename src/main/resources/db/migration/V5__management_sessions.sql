CREATE TABLE brief_management_session (
 digest TEXT PRIMARY KEY,
 subscriber_id BIGINT NOT NULL REFERENCES brief_subscriber(id),
 generation BIGINT NOT NULL,
 csrf_token TEXT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX brief_management_session_expiry ON brief_management_session(expires_at);
