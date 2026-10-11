-- Active: 1790841303608@@127.0.0.1@5432@attend
DO $$ BEGIN
    CREATE TYPE invitation_status AS ENUM ('PENDING', 'ACCEPTED', 'DECLINED');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;
create table if not exists "invitations" (
    id uuid primary key default gen_random_uuid(),
    id_sender uuid not null references users(id) on delete cascade,
    id_receiver uuid not null references users(id) on delete cascade,
    id_event uuid not null references events(id) on delete cascade,
    invitation_status invitation_status default 'PENDING'
);