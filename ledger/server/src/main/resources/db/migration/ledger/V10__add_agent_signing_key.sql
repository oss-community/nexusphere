alter table agent
    add column signing_key        text,
    add column signing_key_id     varchar(64),
    add column signing_key_set_at timestamptz;
