CREATE TABLE brief_delivery (
 id UUID PRIMARY KEY, subscriber_id BIGINT NOT NULL REFERENCES brief_subscriber(id), cutoff TIMESTAMPTZ NOT NULL,
 subscriber_generation BIGINT NOT NULL, kind TEXT NOT NULL, recipient TEXT NOT NULL, sender TEXT NOT NULL, subject TEXT NOT NULL, text_body TEXT NOT NULL, html_body TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'READY', uncertain BOOLEAN NOT NULL DEFAULT FALSE, owner BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMPTZ,
 first_submitted_at TIMESTAMPTZ, last_submitted_at TIMESTAMPTZ, last_result TEXT, provider_id TEXT,
 UNIQUE(subscriber_id, cutoff, kind)
);
CREATE TABLE brief_delivery_release (
 delivery_id UUID NOT NULL REFERENCES brief_delivery(id), subscriber_id BIGINT NOT NULL REFERENCES brief_subscriber(id),
 release_id BIGINT NOT NULL REFERENCES brief_release(id), project TEXT NOT NULL, selected_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(subscriber_id, release_id)
);
CREATE INDEX brief_delivery_retry_idx ON brief_delivery(state, lease_until);
