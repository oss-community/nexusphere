create table a2a_exchange (
    id                uuid         primary key,
    direction         varchar(8)   not null,
    peer              varchar(300) not null,
    request_id        uuid         not null,
    agent_id          varchar(200) not null,
    principal_id      varchar(200) not null,
    method            varchar(120) not null,
    mandate_id        uuid         not null,
    mandate_token     text         not null,
    request_token     text         not null,
    request_hash      char(64)     not null,
    response_hash     char(64),
    status            integer,
    outcome           varchar(16),
    evidence_sequence bigint,
    receipt           text,
    receipt_status    varchar(16),
    created_at        timestamptz  not null,
    unique (direction, peer, request_id)
);

create index a2a_exchange_agent_idx on a2a_exchange (agent_id, created_at);
