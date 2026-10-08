alter table grant_record drop constraint grant_record_status_check;
alter table grant_record add constraint grant_record_status_check
    check (status in ('PENDING', 'ACTIVE', 'DENIED', 'REVOKED'));
alter table grant_record add column consent varchar(20) check (consent in ('OPERATOR', 'PRINCIPAL'));
alter table grant_record add column consented_at timestamptz;
update grant_record set consent = 'OPERATOR', consented_at = created_at where status <> 'PENDING';
