-- =====================================================================
-- V1__init_schema.sql
-- Events & tickets schema (PostgreSQL 13+)
-- gen_random_uuid() is built in from PostgreSQL 13. On older versions,
-- run: CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- =====================================================================

-- ---------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------
CREATE TYPE event_status  AS ENUM ('OK', 'CANCELLED', 'POSTPONED');
CREATE TYPE event_role    AS ENUM ('CREATOR', 'ADMIN');
CREATE TYPE ticket_status AS ENUM ('VALID', 'USED', 'CANCELLED', 'REFUNDED');

-- ---------------------------------------------------------------------
-- updated_at helper
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(255) NOT NULL,
    password_hash varchar(255) NOT NULL,
    full_name     varchar(255) NOT NULL,
    username      varchar(50)  NOT NULL,
    date_birth    date,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- events
-- ---------------------------------------------------------------------
CREATE TABLE events (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    title       varchar(255) NOT NULL,
    description text,
    start_time  timestamptz  NOT NULL,
    end_time    timestamptz  NOT NULL,
    is_public   boolean      NOT NULL DEFAULT true,
    location    varchar(255),
    status      event_status NOT NULL DEFAULT 'OK',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT chk_events_time_range CHECK (end_time > start_time)
);

CREATE INDEX idx_events_start_time ON events (start_time);
CREATE INDEX idx_events_public     ON events (is_public, status, start_time);

CREATE TRIGGER trg_events_updated_at
    BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- event_organizer (who manages which event)
-- ---------------------------------------------------------------------
CREATE TABLE event_organizer (
    id         uuid       PRIMARY KEY DEFAULT gen_random_uuid(),
    id_user    uuid       NOT NULL REFERENCES users (id),
    id_event   uuid       NOT NULL REFERENCES events (id),
    event_role event_role NOT NULL,

    CONSTRAINT uq_event_organizer_user_event UNIQUE (id_user, id_event)
);

CREATE INDEX idx_event_organizer_user  ON event_organizer (id_user);
CREATE INDEX idx_event_organizer_event ON event_organizer (id_event);

-- ---------------------------------------------------------------------
-- ticket_tier (ticket types for an event, e.g. Standard / VIP)
-- ---------------------------------------------------------------------
CREATE TABLE ticket_tier (
    id        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    id_event  uuid          NOT NULL REFERENCES events (id),
    tier_name varchar(100)  NOT NULL,
    price     numeric(10,2) NOT NULL DEFAULT 0,
    capacity  integer       NOT NULL,

    CONSTRAINT chk_ticket_tier_price    CHECK (price >= 0),
    CONSTRAINT chk_ticket_tier_capacity CHECK (capacity >= 0),
    CONSTRAINT uq_ticket_tier_name_event UNIQUE (id_event, tier_name)
);

CREATE INDEX idx_ticket_tier_event ON ticket_tier (id_event);

-- ---------------------------------------------------------------------
-- ticket (ticket.id is what the QR code encodes)
-- ---------------------------------------------------------------------
CREATE TABLE ticket (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    id_owner      uuid          NOT NULL REFERENCES users (id),
    id_checked_by uuid          REFERENCES users (id),
    id_tier       uuid          NOT NULL REFERENCES ticket_tier (id),
    price_paid    numeric(10,2) NOT NULL,
    ticket_status ticket_status NOT NULL DEFAULT 'VALID',
    checked_in_at timestamptz,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    valid_until   timestamptz,

    CONSTRAINT chk_ticket_price_paid CHECK (price_paid >= 0),
    -- scanner and scan time are either both set or both empty
    CONSTRAINT chk_ticket_checkin_pair
        CHECK ((id_checked_by IS NULL) = (checked_in_at IS NULL))
);

CREATE INDEX idx_ticket_owner ON ticket (id_owner);
CREATE INDEX idx_ticket_tier  ON ticket (id_tier);

-- ---------------------------------------------------------------------
-- refresh_token
-- Only the SHA-256 hash of the token is stored, never the raw value.
-- Rotation: when a token is used, it is revoked and points to its
-- replacement via replaced_by, which lets you detect reuse of a stolen
-- token (a revoked token presented again => revoke the whole chain).
-- ---------------------------------------------------------------------
CREATE TABLE refresh_token (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    id_user     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  varchar(64)  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    replaced_by uuid         REFERENCES refresh_token (id) ON DELETE SET NULL,
    user_agent  varchar(255),
    ip_address  varchar(45),

    CONSTRAINT uq_refresh_token_hash   UNIQUE (token_hash),
    CONSTRAINT chk_refresh_token_dates CHECK (expires_at > created_at)
);

CREATE INDEX idx_refresh_token_user    ON refresh_token (id_user);
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);

-- =====================================================================
-- Reference queries (not part of the migration)
-- =====================================================================

-- Atomic check-in: returns a row only if the ticket was still VALID,
-- so two simultaneous scans cannot both succeed.
--
--   UPDATE ticket
--   SET ticket_status = 'USED',
--       checked_in_at = now(),
--       id_checked_by = :scanner_id
--   WHERE id = :ticket_id
--     AND ticket_status = 'VALID'
--   RETURNING id;
--
-- Issuing a ticket without overselling (run inside one transaction):
--
--   SELECT capacity FROM ticket_tier WHERE id = :tier_id FOR UPDATE;
--   SELECT count(*) FROM ticket
--     WHERE id_tier = :tier_id AND ticket_status IN ('VALID', 'USED');
--   -- if count < capacity: INSERT INTO ticket (...)