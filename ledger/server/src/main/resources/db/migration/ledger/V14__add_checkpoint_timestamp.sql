create table checkpoint_timestamp (
    sequence    bigint      primary key references checkpoint (sequence),
    token       bytea       not null,
    gen_time    timestamptz not null,
    recorded_at timestamptz not null
);

create trigger checkpoint_timestamp_append_only
    before update or delete
    on checkpoint_timestamp
    for each row
execute function reject_ledger_change();

create trigger checkpoint_timestamp_truncate_guard
    before truncate
    on checkpoint_timestamp
    for each statement
execute function reject_ledger_change();
