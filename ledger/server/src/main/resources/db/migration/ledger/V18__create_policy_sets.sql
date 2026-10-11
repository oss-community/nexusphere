create table policy_set (
    hash       char(64)     primary key,
    text       text         not null,
    policies   integer      not null,
    created_at timestamptz  not null,
    created_by varchar(200) not null
);

create table policy_activation (
    id           bigserial    primary key,
    hash         char(64)     not null references policy_set (hash),
    activated_at timestamptz  not null,
    activated_by varchar(200) not null
);

create trigger policy_set_append_only
    before update or delete
    on policy_set
    for each row
execute function reject_ledger_change();

create trigger policy_set_truncate_guard
    before truncate
    on policy_set
    for each statement
execute function reject_ledger_change();

create trigger policy_activation_append_only
    before update or delete
    on policy_activation
    for each row
execute function reject_ledger_change();

create trigger policy_activation_truncate_guard
    before truncate
    on policy_activation
    for each statement
execute function reject_ledger_change();
