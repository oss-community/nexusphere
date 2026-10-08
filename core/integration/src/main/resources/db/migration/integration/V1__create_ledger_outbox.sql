create table ledger_outbox (
    id          uuid         primary key,
    seq         bigint       generated always as identity,
    kind        varchar(20)  not null,
    payload     text         not null,
    created_at  timestamptz  not null,
    sent_at     timestamptz,
    rejected_at timestamptz,
    attempts    integer      not null default 0,
    last_error  varchar(500)
);

create index ledger_outbox_pending_idx on ledger_outbox (seq) where sent_at is null and rejected_at is null;

create table ledger_grant (
    grant_id      varchar(64)  primary key,
    delegation_id uuid         not null,
    agent_id      varchar(200) not null,
    created_at    timestamptz  not null,
    revoked_at    timestamptz
);

create unique index ledger_grant_active_idx on ledger_grant (delegation_id) where revoked_at is null;
