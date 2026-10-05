create table transaction (
    id                        uuid         primary key,
    type                      varchar(60)  not null,
    agreement_id              uuid         not null,
    agreement_version         integer      not null,
    capability_id             uuid         not null,
    capability_network_id     uuid         not null,
    requester_organization_id uuid         not null,
    requester_network_id      uuid         not null,
    provider_organization_id  uuid         not null,
    provider_network_id       uuid         not null,
    initiating_principal_id   uuid         not null,
    initiating_identity_id    uuid         not null,
    decision_id               uuid,
    delegation_id             uuid,
    federation_id             uuid,
    trust_relationship_id     uuid,
    metadata                  text         not null,
    status                    varchar(20)  not null,
    reason                    varchar(200),
    result                    text,
    executor_principal_id     uuid,
    executor_identity_id      uuid,
    execution_decision_id     uuid,
    created_at                timestamptz  not null,
    authorized_at             timestamptz,
    started_at                timestamptz,
    finished_at               timestamptz,
    version                   bigint       not null
);

create index transaction_requester_network_idx on transaction (requester_network_id);

create index transaction_provider_network_idx on transaction (provider_network_id);

create index transaction_agreement_idx on transaction (agreement_id);
