create table cluster_member (
    instance_id  varchar(200) primary key,
    started_at   timestamptz  not null,
    heartbeat_at timestamptz  not null,
    leader       boolean      not null
);
