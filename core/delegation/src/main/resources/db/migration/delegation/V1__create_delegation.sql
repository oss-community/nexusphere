create table delegation (
    id                     uuid primary key,
    network_id             uuid        not null,
    delegator_principal_id uuid        not null,
    delegate_principal_id  uuid        not null,
    actions                text        not null,
    capability_types       text        not null,
    networks               text        not null,
    resource_types         text        not null,
    status                 varchar(20) not null,
    valid_from             timestamptz not null,
    valid_until            timestamptz,
    created_at             timestamptz not null,
    revoked_at             timestamptz,
    version                bigint      not null
);

create index delegation_network_idx on delegation (network_id);

create index delegation_delegate_idx on delegation (network_id, delegate_principal_id);
