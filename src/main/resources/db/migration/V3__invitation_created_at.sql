-- Adds a creation timestamp to invitations so lists can be ordered by recency
-- and responses can report when an invitation was sent.
alter table "invitations"
    add column if not exists created_at timestamptz not null default now();
