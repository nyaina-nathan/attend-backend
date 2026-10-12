-- Ticket purchase orders: a buyer submits a PENDING order with a payment reference; an
-- event organizer reviews it and either APPROVES it (tickets are issued) or REFUSES it.
-- This replaces issuing a ticket to whoever asks first.

DO $$ BEGIN
    CREATE TYPE order_status AS ENUM ('PENDING', 'APPROVED', 'REFUSED', 'CANCELLED');
EXCEPTION WHEN duplicate_object THEN null; END $$;

CREATE TABLE IF NOT EXISTS ticket_orders (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    id_owner              uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id_event              uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    id_payment_method     uuid NOT NULL REFERENCES event_payment_methods (id) ON DELETE RESTRICT,
    id_reviewed_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    transaction_reference varchar(255),
    price_unit            varchar(10) NOT NULL DEFAULT 'USD',
    order_status          order_status NOT NULL DEFAULT 'PENDING',
    refusal_reason        text,
    reviewed_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_ticket_orders_refusal
        CHECK (refusal_reason IS NULL OR order_status = 'REFUSED')
);

CREATE INDEX IF NOT EXISTS idx_ticket_orders_owner  ON ticket_orders (id_owner);
CREATE INDEX IF NOT EXISTS idx_ticket_orders_event  ON ticket_orders (id_event);
CREATE INDEX IF NOT EXISTS idx_ticket_orders_method ON ticket_orders (id_payment_method);
CREATE INDEX IF NOT EXISTS idx_ticket_orders_status ON ticket_orders (order_status);

-- Transaction references are unique per payment provider, not globally; NULL allowed
-- (cash has no reference).
CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_orders_reference
    ON ticket_orders (id_payment_method, transaction_reference)
    WHERE transaction_reference IS NOT NULL;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ticket_orders_updated_at') THEN
        CREATE TRIGGER trg_ticket_orders_updated_at
            BEFORE UPDATE ON ticket_orders
            FOR EACH ROW EXECUTE FUNCTION set_updated_at();
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS ticket_order_items (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    id_order   uuid NOT NULL REFERENCES ticket_orders (id) ON DELETE CASCADE,
    id_tier    uuid NOT NULL REFERENCES ticket_tier (id) ON DELETE RESTRICT,
    quantity   integer NOT NULL DEFAULT 1,
    unit_price numeric(10,2) NOT NULL,

    CONSTRAINT chk_ticket_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_ticket_order_items_price    CHECK (unit_price >= 0),
    CONSTRAINT uq_ticket_order_items_tier      UNIQUE (id_order, id_tier)
);

CREATE INDEX IF NOT EXISTS idx_ticket_order_items_order ON ticket_order_items (id_order);
CREATE INDEX IF NOT EXISTS idx_ticket_order_items_tier  ON ticket_order_items (id_tier);

-- Link issued tickets back to the order that produced them (nullable: pre-existing tickets).
ALTER TABLE ticket
    ADD COLUMN IF NOT EXISTS id_order uuid REFERENCES ticket_orders (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_ticket_order ON ticket (id_order);
