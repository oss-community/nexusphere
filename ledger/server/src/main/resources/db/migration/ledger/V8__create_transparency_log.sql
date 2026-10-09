create table merkle_node (
    level smallint not null,
    idx   bigint   not null,
    hash  bytea    not null,
    primary key (level, idx)
);

create table log_checkpoint (
    tree_size  bigint      primary key,
    root_hash  bytea       not null,
    note       text        not null,
    created_at timestamptz not null
);

create table log_cosignature (
    tree_size  bigint       not null references log_checkpoint (tree_size),
    witness    varchar(200) not null,
    line       text         not null,
    created_at timestamptz  not null,
    primary key (tree_size, witness)
);

create table witnessed_log (
    origin     varchar(200) primary key,
    tree_size  bigint       not null,
    root_hash  bytea        not null,
    updated_at timestamptz  not null
);

create trigger merkle_node_append_only
    before update or delete
    on merkle_node
    for each row
execute function reject_ledger_change();

create trigger log_checkpoint_append_only
    before update or delete
    on log_checkpoint
    for each row
execute function reject_ledger_change();

create trigger log_cosignature_append_only
    before update or delete
    on log_cosignature
    for each row
execute function reject_ledger_change();

do
$$
    declare
        current_level smallint := 0;
        added         bigint;
    begin
        insert into merkle_node (level, idx, hash)
        select 0, sequence - 1, sha256('\x00'::bytea || decode(hash, 'hex'))
        from evidence_record;
        loop
            insert into merkle_node (level, idx, hash)
            select current_level + 1, l.idx / 2, sha256('\x01'::bytea || l.hash || r.hash)
            from merkle_node l
                     join merkle_node r on r.level = l.level and r.idx = l.idx + 1
            where l.level = current_level
              and l.idx % 2 = 0;
            get diagnostics added = row_count;
            exit when added = 0;
            current_level := current_level + 1;
        end loop;
    end
$$;
