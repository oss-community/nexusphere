create table audit_event (
    id                          uuid         primary key,
    recorded_order              bigint       generated always as identity,
    source_event_id             uuid         not null,
    event_type                  varchar(120) not null,
    occurred_at                 timestamptz  not null,
    recorded_at                 timestamptz  not null,
    network_id                  uuid         not null,
    principal_id                uuid,
    identity_id                 uuid,
    accountable_organization_id uuid,
    action                      varchar(120) not null,
    resource_type               varchar(60),
    resource_id                 varchar(200),
    federation_id               uuid,
    delegation_id               uuid,
    agreement_id                uuid,
    transaction_id              uuid,
    decision_id                 uuid,
    result                      varchar(20)  not null,
    reason                      varchar(200),
    correlation_id              varchar(100) not null,
    causation_id                uuid,
    metadata                    text         not null,
    unique (source_event_id, network_id)
);

create index audit_event_network_idx on audit_event (network_id, occurred_at);

create index audit_event_transaction_idx on audit_event (transaction_id);

create index audit_event_agreement_idx on audit_event (agreement_id);

create index audit_event_correlation_idx on audit_event (correlation_id);

create function reject_audit_change() returns trigger
    language plpgsql as
$$
begin
    raise exception 'audit events are append-only';
end;
$$;

create trigger audit_event_append_only
    before update or delete
    on audit_event
    for each row
execute function reject_audit_change();
