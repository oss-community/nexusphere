create table trust_relationship (
    id                uuid primary key,
    source_type       varchar(20) not null,
    source_id         uuid        not null,
    source_network_id uuid        not null,
    target_type       varchar(20) not null,
    target_id         uuid        not null,
    target_network_id uuid        not null,
    scopes            text        not null,
    level             varchar(20) not null,
    status            varchar(20) not null,
    effective_from    timestamptz not null,
    effective_until   timestamptz,
    created_at        timestamptz not null,
    revoked_at        timestamptz,
    version           bigint      not null
);

create index trust_relationship_source_network_idx on trust_relationship (source_network_id);

create index trust_relationship_target_network_idx on trust_relationship (target_network_id);

create index trust_relationship_pair_idx on trust_relationship (source_type, source_id, target_type, target_id);
