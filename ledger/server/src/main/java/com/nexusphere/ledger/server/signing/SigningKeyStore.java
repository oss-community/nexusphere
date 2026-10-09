package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.SigningKeys;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class SigningKeyStore {

    private static final String SELECT = """
            select key_id, public_key, previous_key_id, activated_at, retired_at, key_signature,
                   previous_key_signature, evidence_sequence, compromised_at, revoked_at, revocation_reason,
                   revoker_key_id, revocation_signature
            from ledger.signing_key
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SigningKeyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock() {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext('ledger.signing_key'))", Map.of());
    }

    public List<SigningKey> all() {
        return jdbc.query(SELECT + " order by activated_at, key_id", Map.of(), SigningKeyStore::row);
    }

    void insert(SigningKey key) {
        KeyRotation rotation = key.rotation();
        jdbc.update("""
                insert into ledger.signing_key (key_id, algorithm, public_key, previous_key_id, activated_at,
                                                key_signature, previous_key_signature)
                values (:keyId, :algorithm, :publicKey, :previousKeyId, :activatedAt, :keySignature,
                        :previousKeySignature)
                """, new MapSqlParameterSource()
                .addValue("keyId", key.keyId())
                .addValue("algorithm", SigningKeys.ALGORITHM)
                .addValue("publicKey", key.publicKey().encoded())
                .addValue("previousKeyId", key.previousKeyId())
                .addValue("activatedAt", Timestamp.from(key.activatedAt()))
                .addValue("keySignature", rotation == null ? null : rotation.keySignature())
                .addValue("previousKeySignature", rotation == null ? null : rotation.previousKeySignature()));
    }

    void retire(String keyId, Instant retiredAt) {
        jdbc.update("update ledger.signing_key set retired_at = :retiredAt where key_id = :keyId",
                new MapSqlParameterSource().addValue("keyId", keyId).addValue("retiredAt", Timestamp.from(retiredAt)));
    }

    void revoke(KeyRevocation revocation) {
        jdbc.update("""
                update ledger.signing_key
                set compromised_at = :compromisedAt, revoked_at = :revokedAt, revocation_reason = :reason,
                    revoker_key_id = :revokerKeyId, revocation_signature = :signature
                where key_id = :keyId
                """, new MapSqlParameterSource()
                .addValue("keyId", revocation.keyId())
                .addValue("compromisedAt", Timestamp.from(revocation.compromisedAt()))
                .addValue("revokedAt", Timestamp.from(revocation.revokedAt()))
                .addValue("reason", revocation.reason())
                .addValue("revokerKeyId", revocation.revokerKeyId())
                .addValue("signature", revocation.signature()));
    }

    public void markRecorded(String keyId, long sequence) {
        jdbc.update("update ledger.signing_key set evidence_sequence = :sequence where key_id = :keyId",
                Map.of("keyId", keyId, "sequence", sequence));
    }

    private static SigningKey row(ResultSet rs, int rowNumber) throws SQLException {
        SigningKeys.PublicKeyInfo publicKey = SigningKeys.PublicKeyInfo.of(
                SigningKeys.decodePublic(rs.getString("public_key")));
        Instant activatedAt = rs.getTimestamp("activated_at").toInstant();
        Timestamp retiredAt = rs.getTimestamp("retired_at");
        String previousKeyId = rs.getString("previous_key_id");
        KeyRotation rotation = previousKeyId == null ? null : new KeyRotation(publicKey.keyId(), publicKey.encoded(),
                previousKeyId, activatedAt, rs.getString("key_signature"), rs.getString("previous_key_signature"));
        long sequence = rs.getLong("evidence_sequence");
        Long evidenceSequence = rs.wasNull() ? null : sequence;
        Timestamp compromisedAt = rs.getTimestamp("compromised_at");
        KeyRevocation revocation = compromisedAt == null ? null : new KeyRevocation(publicKey.keyId(),
                compromisedAt.toInstant(), rs.getTimestamp("revoked_at").toInstant(),
                rs.getString("revocation_reason"), rs.getString("revoker_key_id"),
                rs.getString("revocation_signature"));
        return new SigningKey(publicKey, activatedAt, retiredAt == null ? null : retiredAt.toInstant(), rotation,
                evidenceSequence, revocation);
    }
}
