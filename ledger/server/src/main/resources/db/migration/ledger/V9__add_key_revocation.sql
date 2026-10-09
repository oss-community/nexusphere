alter table signing_key
    add column compromised_at       timestamptz,
    add column revoked_at           timestamptz,
    add column revocation_reason    text,
    add column revoker_key_id       varchar(64) references signing_key (key_id),
    add column revocation_signature text;
