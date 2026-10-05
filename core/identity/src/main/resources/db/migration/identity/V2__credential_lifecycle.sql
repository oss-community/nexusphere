alter table credential add column expires_at timestamptz;

update credential set expires_at = created_at + interval '90 days';

alter table credential alter column expires_at set not null;

alter table credential add column revoked_at timestamptz;
