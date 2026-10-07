create trigger evidence_attribute_truncate_guard
    before truncate
    on evidence_attribute
    for each statement
execute function reject_ledger_change();

create trigger checkpoint_truncate_guard
    before truncate
    on checkpoint
    for each statement
execute function reject_ledger_change();

create table agent (
    agent_id   varchar(200) primary key,
    name       varchar(200) not null,
    owner_id   varchar(200) not null,
    status     varchar(20)  not null check (status in ('ACTIVE', 'DISABLED')),
    key_hash   char(64)     not null unique,
    key_prefix varchar(16)  not null,
    created_at timestamptz  not null
);

create table grant_record (
    id            uuid           primary key,
    seq           bigint         generated always as identity unique,
    principal_id  varchar(200)   not null,
    agent_id      varchar(200)   not null references agent (agent_id),
    actions       varchar(120)[] not null,
    targets       varchar(300)[] not null,
    not_before    timestamptz,
    expires_at    timestamptz    not null,
    max_uses      bigint,
    uses          bigint         not null default 0,
    status        varchar(20)    not null check (status in ('ACTIVE', 'REVOKED')),
    terms_hash    char(64)       not null,
    reason        varchar(500),
    created_at    timestamptz    not null,
    revoked_at    timestamptz,
    revoke_reason varchar(500),
    check (max_uses is null or uses <= max_uses)
);

create index grant_record_agent_principal_idx on grant_record (agent_id, principal_id, status);

create table decision (
    id                  uuid         primary key references evidence_record (id),
    agent_id            varchar(200) not null,
    principal_id        varchar(200) not null,
    action              varchar(120) not null,
    target              varchar(300),
    decision            varchar(10)  not null check (decision in ('ALLOW', 'DENY')),
    reason_code         varchar(40)  not null,
    grant_id            uuid references grant_record (id),
    decided_at          timestamptz  not null,
    outcome             varchar(20)  check (outcome in ('SUCCEEDED', 'FAILED')),
    outcome_evidence_id uuid references evidence_record (id)
);

create index decision_agent_idx on decision (agent_id, decided_at);
