create table approval (
    id               uuid         primary key,
    fingerprint      char(64)     not null,
    agent_id         varchar(200) not null,
    principal_id     varchar(200) not null,
    action           varchar(120) not null,
    target           varchar(300),
    input_hash       char(64),
    policy_hash      char(64)     not null references policy_set (hash),
    policies         varchar(1000) not null,
    approver         varchar(20)  not null check (approver in ('principal', 'operator', 'any')),
    status           varchar(20)  not null check (status in ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED')),
    requested_at     timestamptz  not null,
    expires_at       timestamptz  not null,
    resolved_at      timestamptz,
    resolved_by      varchar(200),
    method           varchar(20)  check (method in ('operator', 'principal', 'ciba', 'timeout')),
    reason           varchar(500),
    evidence_id      uuid references evidence_record (id),
    closed_at        timestamptz,
    notified_at      timestamptz,
    ciba_request_id  varchar(500),
    ciba_poll_at     timestamptz
);

create index approval_fingerprint on approval (fingerprint, requested_at desc);
create index approval_open on approval (status, expires_at) where status = 'PENDING';
create index approval_principal on approval (principal_id, requested_at desc);
