CREATE TABLE jobs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type             VARCHAR(100) NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority         SMALLINT     NOT NULL DEFAULT 2,  -- JobPriority: 1=LOW, 2=NORMAL, 3=HIGH
    status           VARCHAR(20)  NOT NULL,
    run_at           TIMESTAMPTZ  NOT NULL,            -- earliest time the job may run

    attempt_count    INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    last_error       TEXT,

    idempotency_key  VARCHAR(255) UNIQUE,              -- NULLs don't conflict, so the key stays optional

    worker_id        VARCHAR(100),                     -- worker currently holding the job
    lease_expires_at TIMESTAMPTZ,                      -- past this, the worker is presumed dead

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,

    CONSTRAINT jobs_status_check
        CHECK (status IN ('SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT jobs_priority_check CHECK (priority BETWEEN 1 AND 3),
    CONSTRAINT jobs_attempts_check CHECK (attempt_count >= 0 AND max_attempts >= 1)
);

-- The scheduler repeatedly asks "which SCHEDULED jobs are due?". A partial index covers only
-- those rows, so it stays small no matter how many finished jobs accumulate.
CREATE INDEX jobs_due_idx ON jobs (run_at) WHERE status = 'SCHEDULED';
