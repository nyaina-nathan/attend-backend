-- Hardens event_payment_methods (created in V7): enforces defaults + NOT NULL on
-- is_active / created_at / updated_at, indexes the event FK, keeps updated_at
-- current via the shared set_updated_at() trigger from V1, and constrains
-- payment_type to the values known to the domain (PaymentType enum).

-- 1. Backfill any pre-existing NULLs before tightening the constraints.
UPDATE event_payment_methods SET is_active = TRUE WHERE is_active IS NULL;
UPDATE event_payment_methods SET created_at = now() WHERE created_at IS NULL;
UPDATE event_payment_methods SET updated_at = now() WHERE updated_at IS NULL;

-- 2. Defaults + NOT NULL.
ALTER TABLE event_payment_methods
    ALTER COLUMN is_active SET DEFAULT TRUE,
    ALTER COLUMN is_active SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL;

-- 3. Index the event FK (matches the other child tables).
CREATE INDEX IF NOT EXISTS idx_event_payment_methods_event
    ON event_payment_methods (id_event);

-- 4. Restrict payment_type to the values known to the domain.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_event_payment_methods_type'
    ) THEN
        ALTER TABLE event_payment_methods
            ADD CONSTRAINT chk_event_payment_methods_type
                CHECK (payment_type IN (
                    'BANK_TRANSFER',
                    'MOBILE_MONEY',
                    'CARD',
                    'CRYPTO',
                    'CASH',
                    'OTHER'
                ));
    END IF;
END $$;
