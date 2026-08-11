ALTER TABLE jobs ADD COLUMN idempotency_key TEXT;

-- La unicidad es por API key, no global: dos clientes distintos pueden usar el
-- mismo Idempotency-Key sin colisionar. El índice parcial deja fuera los jobs
-- que no mandaron header, que son la mayoría.
CREATE UNIQUE INDEX uq_jobs_idempotency ON jobs (api_key_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
