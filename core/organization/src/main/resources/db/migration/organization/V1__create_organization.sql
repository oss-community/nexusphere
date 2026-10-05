create table organization (
    id         uuid primary key,
    network_id uuid         not null,
    name       varchar(120) not null,
    status     varchar(20)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null
);

create unique index organization_network_name_uk on organization (network_id, lower(name));
