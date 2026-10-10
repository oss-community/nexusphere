alter table evidence_record
    add column model text;

create table prompt (
    hash       char(64)     primary key,
    text       text         not null,
    created_at timestamptz  not null,
    created_by varchar(200) not null
);

create trigger prompt_append_only
    before update or delete
    on prompt
    for each row
execute function reject_ledger_change();

create trigger prompt_truncate_guard
    before truncate
    on prompt
    for each statement
execute function reject_ledger_change();
