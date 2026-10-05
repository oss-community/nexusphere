create table network (
    id          uuid primary key,
    name        varchar(120) not null,
    description varchar(500),
    status      varchar(20)  not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null
);

create unique index network_name_uk on network (lower(name));
