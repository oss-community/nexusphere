alter table a2a_exchange add column use_counted boolean not null default false;

create index a2a_exchange_mandate_idx on a2a_exchange (peer, mandate_id) where direction = 'INBOUND';
