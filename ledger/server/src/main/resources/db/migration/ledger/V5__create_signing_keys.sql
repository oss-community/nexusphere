create table signing_key (
    key_id                 varchar(64) primary key,
    algorithm              varchar(20) not null,
    public_key             text        not null,
    previous_key_id        varchar(64) references signing_key (key_id),
    activated_at           timestamptz not null,
    retired_at             timestamptz,
    key_signature          text,
    previous_key_signature text,
    evidence_sequence      bigint
);

create unique index signing_key_active_idx on signing_key ((true)) where retired_at is null;
