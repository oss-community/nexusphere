create table anomaly (
    id                   uuid             primary key,
    agent_id             varchar(200)     not null,
    kind                 varchar(20)      not null check (kind in ('VOLUME', 'DENIALS', 'FAILURES')),
    status               varchar(20)      not null check (status in ('OPEN', 'RESOLVED')),
    observed             double precision not null,
    expected             double precision not null,
    score                double precision not null,
    window_start         timestamptz      not null,
    window_end           timestamptz      not null,
    detected_at          timestamptz      not null,
    resolved_at          timestamptz,
    evidence_id          uuid             not null references evidence_record (id),
    resolved_evidence_id uuid references evidence_record (id)
);

create unique index anomaly_open on anomaly (agent_id, kind) where status = 'OPEN';
create index anomaly_detected on anomaly (detected_at desc);
create index evidence_record_recorded_idx on evidence_record (recorded_at);
