create table legal_hold (
    id             uuid         primary key,
    principal_ref  uuid         not null references principal_key (principal_ref),
    reason         varchar(500) not null,
    placed_at      timestamptz  not null,
    released_at    timestamptz,
    release_reason varchar(500)
);

create index legal_hold_active_idx on legal_hold (principal_ref) where released_at is null;

create index evidence_record_occurred_idx on evidence_record (occurred_at);
