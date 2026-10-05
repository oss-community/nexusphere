create table identity (
    id                     uuid primary key,
    type                   varchar(20)  not null,
    display_name           varchar(120) not null,
    owning_network_id      uuid,
    owning_organization_id uuid,
    agent_provider         varchar(100),
    agent_model            varchar(100),
    status                 varchar(20)  not null,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null
);

create table credential (
    id          uuid primary key,
    identity_id uuid        not null references identity (id),
    secret_hash varchar(64) not null,
    created_at  timestamptz not null
);

create index credential_identity_idx on credential (identity_id);
