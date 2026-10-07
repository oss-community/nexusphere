create table ledger_head (
    id       smallint primary key check (id = 1),
    sequence bigint   not null,
    hash     char(64) not null
);

insert into ledger_head (id, sequence, hash)
values (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

create table evidence_record (
    id             uuid         primary key,
    sequence       bigint       not null unique,
    occurred_at    timestamptz  not null,
    recorded_at    timestamptz  not null,
    agent_id       varchar(200) not null,
    principal_id   varchar(200) not null,
    action         varchar(120) not null,
    target         varchar(300),
    decision       varchar(10),
    reason         varchar(500),
    delegation_id  varchar(200),
    input_hash     char(64),
    output_hash    char(64),
    outcome        varchar(20)  not null,
    correlation_id varchar(128),
    previous_hash  char(64)     not null,
    hash           char(64)     not null unique
);

create index evidence_record_agent_idx on evidence_record (agent_id, sequence);

create index evidence_record_principal_idx on evidence_record (principal_id, sequence);

create table evidence_attribute (
    evidence_id uuid          not null references evidence_record (id),
    name        varchar(64)   not null,
    value       varchar(1024) not null,
    primary key (evidence_id, name)
);

create table checkpoint (
    sequence   bigint      primary key,
    head_hash  char(64)    not null,
    created_at timestamptz not null,
    key_id     varchar(32) not null,
    signature  text        not null
);

create function reject_ledger_change() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only', tg_table_name;
end;
$$;

create trigger evidence_record_append_only
    before update or delete
    on evidence_record
    for each row
execute function reject_ledger_change();

create trigger evidence_attribute_append_only
    before update or delete
    on evidence_attribute
    for each row
execute function reject_ledger_change();

create trigger checkpoint_append_only
    before update or delete
    on checkpoint
    for each row
execute function reject_ledger_change();

create trigger ledger_truncate_guard
    before truncate
    on evidence_record
    for each statement
execute function reject_ledger_change();
