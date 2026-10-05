create table agreement (
    id                           uuid         primary key,
    type                         varchar(60)  not null,
    title                        varchar(200) not null,
    capability_id                uuid         not null,
    capability_network_id        uuid         not null,
    capability_type_code         varchar(120) not null,
    proposer_organization_id     uuid         not null,
    proposer_network_id          uuid         not null,
    counterparty_organization_id uuid         not null,
    counterparty_network_id      uuid         not null,
    status                       varchar(20)  not null,
    current_version              integer      not null,
    created_at                   timestamptz  not null,
    activated_at                 timestamptz,
    closed_at                    timestamptz,
    closing_reason               text,
    version                      bigint       not null
);

create index agreement_proposer_network_idx on agreement (proposer_network_id);

create index agreement_counterparty_network_idx on agreement (counterparty_network_id);

create table agreement_version (
    id                        uuid         primary key,
    agreement_id              uuid         not null references agreement (id),
    number                    integer      not null,
    terms                     text         not null,
    changes                   text         not null,
    on_behalf_organization_id uuid         not null,
    on_behalf_network_id      uuid         not null,
    proposed_by               uuid         not null,
    proposed_by_identity      uuid         not null,
    delegation_id             uuid,
    decision_id               uuid,
    federation_id             uuid,
    proposed_at               timestamptz  not null,
    accepted_by               uuid,
    accepted_at               timestamptz,
    acceptance_decision_id    uuid,
    superseded                boolean      not null,
    unique (agreement_id, number)
);
