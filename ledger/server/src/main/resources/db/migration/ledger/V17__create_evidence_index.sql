create table index_entry (
    key      char(64) not null,
    sequence bigint   not null,
    primary key (key, sequence)
);

create index index_entry_sequence_idx on index_entry (sequence);

create table index_root (
    sequence   bigint      primary key references checkpoint (sequence),
    head_hash  char(64)    not null,
    root       char(64)    not null,
    created_at timestamptz not null,
    key_id     varchar(32) not null,
    signature  text        not null
);

create trigger index_entry_append_only
    before update or delete
    on index_entry
    for each row
execute function reject_ledger_change();

create trigger index_entry_truncate_guard
    before truncate
    on index_entry
    for each statement
execute function reject_ledger_change();

create trigger index_root_append_only
    before update or delete
    on index_root
    for each row
execute function reject_ledger_change();

create trigger index_root_truncate_guard
    before truncate
    on index_root
    for each statement
execute function reject_ledger_change();
