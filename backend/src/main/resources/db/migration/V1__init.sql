CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    name        TEXT        NOT NULL,
    key_hash    TEXT        NOT NULL UNIQUE, -- SHA-256 del key; nunca guardamos el key en claro
    tier        TEXT        NOT NULL CHECK (tier IN ('FREE', 'PREMIUM')),
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE jobs (
    id            UUID PRIMARY KEY,
    api_key_id    UUID        NOT NULL REFERENCES api_keys (id),
    model         TEXT        NOT NULL,
    job_type      TEXT        NOT NULL CHECK (job_type IN ('COMPLETION', 'EMBEDDING', 'CLASSIFICATION')),
    prompt        TEXT        NOT NULL,
    priority      TEXT        NOT NULL CHECK (priority IN ('PRIORITY', 'STANDARD')),
    status        TEXT        NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'DONE', 'FAILED', 'EXPIRED', 'DEAD')),
    result        TEXT,
    error         TEXT,
    tokens_used   INT,
    retry_count   INT         NOT NULL DEFAULT 0,
    -- Worker que tomó el job por última vez; sirve para diagnosticar reclaims.
    claimed_by    TEXT,
    stream_msg_id TEXT,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ
);

CREATE INDEX idx_jobs_api_key_created ON jobs (api_key_id, created_at DESC);
CREATE INDEX idx_jobs_status ON jobs (status);
-- El barrido de TTL sólo mira jobs que todavía no terminaron.
CREATE INDEX idx_jobs_expires_at ON jobs (expires_at) WHERE status IN ('QUEUED', 'PROCESSING');
