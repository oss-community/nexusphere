create table checkpoint_rekor (
    sequence    bigint      primary key references checkpoint (sequence),
    log_index   bigint      not null,
    entry       text        not null,
    recorded_at timestamptz not null
);

create trigger checkpoint_rekor_append_only
    before update or delete
    on checkpoint_rekor
    for each row
execute function reject_ledger_change();

create trigger checkpoint_rekor_truncate_guard
    before truncate
    on checkpoint_rekor
    for each statement
execute function reject_ledger_change();
