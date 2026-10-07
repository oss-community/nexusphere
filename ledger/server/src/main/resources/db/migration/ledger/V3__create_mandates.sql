create table mandate (
    id            uuid         primary key,
    status_index  bigint       generated always as identity (minvalue 0 start with 0) unique,
    grant_id      uuid         not null references grant_record (id),
    agent_id      varchar(200) not null,
    principal_id  varchar(200) not null,
    audience      varchar(300),
    issued_at     timestamptz  not null,
    expires_at    timestamptz  not null,
    token         text         not null,
    revoked_at    timestamptz,
    revoke_reason varchar(500)
);

create index mandate_grant_idx on mandate (grant_id);

create index mandate_agent_idx on mandate (agent_id, issued_at);
