create table membership (
    id              uuid primary key,
    identity_id     uuid        not null,
    network_id      uuid        not null,
    organization_id uuid,
    status          varchar(20) not null,
    joined_at       timestamptz not null,
    terminated_at   timestamptz,
    version         bigint      not null
);

create unique index membership_active_uk on membership (identity_id, network_id) where status = 'ACTIVE';

create index membership_network_idx on membership (network_id);
