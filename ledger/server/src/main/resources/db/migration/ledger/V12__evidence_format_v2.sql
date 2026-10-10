do
$$
    begin
        if exists (select 1 from evidence_record) then
            raise exception 'Evidence format v2 replaces v1 and needs an empty ledger: recreate the ledger database';
        end if;
    end
$$;

drop table evidence_attribute;

alter table evidence_record
    drop column principal_id,
    drop column target,
    drop column reason,
    drop column correlation_id,
    add column principal_ref uuid not null,
    add column commitments   text not null;

create index evidence_record_principal_idx on evidence_record (principal_ref, sequence);

create index evidence_record_action_idx on evidence_record (action, sequence);

create table principal_key (
    principal_ref uuid         primary key,
    principal_id  varchar(200) unique,
    data_key      bytea,
    created_at    timestamptz  not null,
    erased_at     timestamptz
);

create table evidence_personal (
    evidence_id   uuid  primary key references evidence_record (id),
    principal_ref uuid  not null references principal_key (principal_ref),
    nonce         bytea not null,
    ciphertext    bytea not null
);

create index evidence_personal_principal_idx on evidence_personal (principal_ref);

create table principal_erasure (
    id            uuid         primary key,
    principal_ref uuid         not null references principal_key (principal_ref),
    requested_at  timestamptz  not null,
    reason        varchar(500),
    completed_at  timestamptz
);

create index principal_erasure_open_idx on principal_erasure (principal_ref) where completed_at is null;
