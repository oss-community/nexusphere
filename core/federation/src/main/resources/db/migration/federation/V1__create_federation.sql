create table federation (
    id                  uuid primary key,
    proposer_network_id uuid        not null,
    partner_network_id  uuid        not null,
    scopes              text        not null,
    status              varchar(30) not null,
    effective_from      timestamptz,
    effective_until     timestamptz,
    suspended_by        uuid,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    version             bigint      not null
);

create unique index federation_open_pair_uk
    on federation (least(proposer_network_id, partner_network_id), greatest(proposer_network_id, partner_network_id))
    where status in ('PROPOSED', 'PENDING_ACCEPTANCE', 'ACTIVE', 'SUSPENDED');

create index federation_proposer_idx on federation (proposer_network_id);

create index federation_partner_idx on federation (partner_network_id);
