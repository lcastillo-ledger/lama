
TRUNCATE TABLE operation;

ALTER TABLE public.operation ADD COLUMN uid VARCHAR NOT NULL;

ALTER TABLE public.operation DROP CONSTRAINT operation_pkey;

ALTER TABLE public.operation ADD CONSTRAINT operation_pkey PRIMARY KEY (uid);

CREATE UNIQUE INDEX operation_compute_balance ON operation (account_id, "time", hash, operation_type);

