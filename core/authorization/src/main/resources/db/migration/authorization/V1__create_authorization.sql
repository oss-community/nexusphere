create table role_assignment (
    id           uuid primary key,
    network_id   uuid        not null,
    principal_id uuid        not null,
    role         varchar(40) not null,
    status       varchar(20) not null,
    assigned_by  uuid,
    assigned_at  timestamptz not null,
    revoked_at   timestamptz,
    version      bigint      not null
);

create unique index role_assignment_active_uk on role_assignment (network_id, principal_id, role)
    where status = 'ACTIVE';

create table authorization_decision (
    id                    uuid primary key,
    principal_id          uuid         not null,
    network_id            uuid         not null,
    target_network_id     uuid         not null,
    action                varchar(60)  not null,
    resource_type         varchar(60),
    resource_id           varchar(200),
    allowed               boolean      not null,
    reason                varchar(60)  not null,
    matched_role          varchar(40),
    delegation_id         uuid,
    federation_id         uuid,
    trust_relationship_id uuid,
    decided_at            timestamptz  not null
);

create index authorization_decision_network_idx on authorization_decision (network_id, decided_at);
