create table capability_type (
    id           uuid primary key,
    code         varchar(100) not null,
    name         varchar(200) not null,
    description  text,
    type_version integer      not null,
    schema       text         not null,
    created_at   timestamptz  not null
);

create unique index capability_type_code_version_uk on capability_type (code, type_version);

create table capability (
    id                          uuid primary key,
    network_id                  uuid         not null,
    owner_type                  varchar(20)  not null,
    owner_id                    uuid         not null,
    accountable_organization_id uuid,
    name                        varchar(200) not null,
    description                 text,
    type_id                     uuid         not null references capability_type (id),
    type_code                   varchar(100) not null,
    type_version                integer      not null,
    specification               text         not null,
    visibility                  varchar(20)  not null,
    status                      varchar(20)  not null,
    created_at                  timestamptz  not null,
    published_at                timestamptz,
    withdrawn_at                timestamptz,
    version                     bigint       not null
);

create unique index capability_owner_name_uk on capability (network_id, owner_type, owner_id, lower(name))
    where status <> 'WITHDRAWN';

create index capability_network_idx on capability (network_id);
