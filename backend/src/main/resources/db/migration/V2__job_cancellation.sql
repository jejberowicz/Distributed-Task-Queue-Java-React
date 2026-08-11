-- CANCELED es un estado terminal más: el job lo cierra el cliente, no el worker.
ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check
    CHECK (status IN ('QUEUED', 'PROCESSING', 'DONE', 'FAILED', 'EXPIRED', 'DEAD', 'CANCELED'));

ALTER TABLE jobs ADD COLUMN canceled_at TIMESTAMPTZ;
