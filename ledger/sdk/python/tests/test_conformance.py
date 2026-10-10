import base64
import unittest

from nexusphere_ledger import merkle
from nexusphere_ledger.canonical import canonical_json, sha256_hex
from nexusphere_ledger.cose import EvidenceStatement, LogReceipt
from nexusphere_ledger.evidence import (ChainVerifier, Checkpoint, EvidenceLink, canonical_content, content_hash,
                                        link_hash)
from nexusphere_ledger.keys import (KeyRevocation, KeyRotation, PrivateKey, PublicKey, signed_revocation_bytes,
                                    signed_rotation_bytes, trusted_and_revoked)
from nexusphere_ledger.mandate import (KEY_BINDING_INVALID, KEY_BINDING_MISSING, MandateVerifier, StaticKeys,
                                       present_bound, sd_hash)
from nexusphere_ledger.note import COSIGNATURE, ED25519, LogCheckpoint, Note, NoteKey, cosign

from .support import ISSUER, LEDGER_KEY, ORIGIN, WITNESS, WITNESS_KEY, vector


def chain_entries():
    return [dict(item["content"], sequence=item["sequence"], previousHash=item["previousHash"], hash=item["hash"])
            for item in vector("evidence-chain.json")["entries"]]


class KeysVector(unittest.TestCase):

    def test_keys(self):
        v = vector("keys.json")
        ledger = PrivateKey.from_seed(bytes.fromhex(v["ledgerSeed"]))
        witness = PrivateKey.from_seed(bytes.fromhex(v["witnessSeed"]))
        self.assertEqual(v["ledgerPrivateKey"], ledger.encoded)
        self.assertEqual(v["ledgerPublicKey"], ledger.public_key.encoded)
        self.assertEqual(v["ledgerKeyId"], ledger.key_id)
        self.assertEqual(v["witnessPublicKey"], witness.public_key.encoded)
        self.assertEqual(v["logVerifierKey"], NoteKey(ORIGIN, ED25519, ledger.public_key).vkey)
        self.assertEqual(v["witnessVerifierKey"], NoteKey(WITNESS, COSIGNATURE, witness.public_key).vkey)
        self.assertEqual(ledger.key_id, PrivateKey.from_base64(v["ledgerPrivateKey"]).key_id)
        self.assertEqual(NoteKey.parse(v["logVerifierKey"]).public_key, PublicKey.from_base64(v["ledgerPublicKey"]))


class CanonicalJsonVector(unittest.TestCase):

    def test_canonical_json(self):
        for item in vector("canonical-json.json"):
            self.assertEqual(item["canonical"], canonical_json(item["input"]))
            self.assertEqual(item["sha256"], sha256_hex(item["canonical"]))


class EvidenceChainVector(unittest.TestCase):

    def test_entries(self):
        v = vector("evidence-chain.json")
        verifier = ChainVerifier()
        for item, entry in zip(v["entries"], chain_entries()):
            self.assertEqual(item["canonicalContent"], canonical_json(canonical_content(entry)))
            self.assertEqual(item["contentHash"], content_hash(entry))
            self.assertEqual(item["hash"], link_hash(item["sequence"], item["previousHash"], item["contentHash"]))
            self.assertTrue(verifier.accept(entry))
        result = verifier.result()
        self.assertTrue(result.valid)
        self.assertEqual(3, result.checked_entries)

    def test_checkpoint(self):
        v = vector("evidence-chain.json")
        head = v["entries"][-1]["hash"]
        checkpoint = Checkpoint(3, head, "2026-10-01T08:01:00.123456Z", LEDGER_KEY.key_id)
        self.assertEqual(v["checkpoint"]["signedBytes"], checkpoint.signed_bytes().decode("utf-8"))
        signed = checkpoint.sign(LEDGER_KEY)
        self.assertEqual(v["checkpoint"]["signature"], signed.signature)
        self.assertTrue(signed.verify(LEDGER_KEY.public_key))

    def test_changed_content_breaks_the_chain(self):
        entries = chain_entries()
        entries[1]["target"] = "delete_invoice"
        verifier = ChainVerifier()
        self.assertTrue(verifier.accept(entries[0]))
        self.assertFalse(verifier.accept(entries[1]))
        self.assertEqual(2, verifier.result().failed_sequence)
        self.assertEqual("content does not match its hash", verifier.result().failure)


class ComplianceCheckpointVector(unittest.TestCase):

    def test_profile_digest(self):
        v = vector("compliance-checkpoint.json")
        self.assertEqual(v["canonicalProfile"], canonical_json(v["profile"]))
        self.assertEqual(v["profileDigest"], sha256_hex(canonical_json(v["profile"]).encode("utf-8")))

    def test_checkpoint_signs_the_profiles(self):
        v = vector("compliance-checkpoint.json")
        node = v["checkpoint"]
        checkpoint = Checkpoint.of(node)
        self.assertEqual(node["format"], checkpoint.format)
        self.assertEqual(node["signedBytes"], checkpoint.signed_bytes().decode("utf-8"))
        self.assertEqual(node["signature"], checkpoint.sign(LEDGER_KEY).signature)
        self.assertTrue(checkpoint.verify(LEDGER_KEY.public_key))
        changed = Checkpoint.of(dict(node, profiles=node["profiles"][:1]))
        self.assertFalse(changed.verify(LEDGER_KEY.public_key))


class MerkleTreeVector(unittest.TestCase):

    def setUp(self):
        self.v = vector("merkle-tree.json")
        self.leaves = [merkle.leaf_hash(bytes([i])) for i in range(8)]

    def test_leaves_and_roots(self):
        self.assertEqual(self.v["leafHashes"], [leaf.hex() for leaf in self.leaves])
        for item in self.v["roots"]:
            self.assertEqual(item["root"], merkle.root(self.leaves[:item["size"]]).hex())

    def test_inclusion_proofs(self):
        for item in self.v["inclusionProofs"]:
            size, index = item["size"], item["index"]
            proof = merkle.inclusion_proof(self.leaves[:size], index)
            self.assertEqual(item["proof"], [p.hex() for p in proof])
            root = merkle.root(self.leaves[:size])
            self.assertTrue(merkle.verify_inclusion(self.leaves[index], index, size, proof, root))
            if proof:
                self.assertFalse(merkle.verify_inclusion(self.leaves[index], index, size, proof[:-1], root))

    def test_consistency_proofs(self):
        for item in self.v["consistencyProofs"]:
            first, second = item["first"], item["second"]
            proof = merkle.consistency_proof(self.leaves[:second], first)
            self.assertEqual(item["proof"], [p.hex() for p in proof])
            self.assertTrue(merkle.verify_consistency(first, second, proof, merkle.root(self.leaves[:first]),
                                                      merkle.root(self.leaves[:second])))
            if first < second and first > 0:
                self.assertFalse(merkle.verify_consistency(first, second, proof, merkle.root(self.leaves[:second]),
                                                           merkle.root(self.leaves[:second])))


class SignedNoteVector(unittest.TestCase):

    def test_note_and_cosignature(self):
        v = vector("signed-note.json")
        leaves = [merkle.leaf_hash(bytes.fromhex(e["hash"])) for e in vector("evidence-chain.json")["entries"]]
        root = merkle.root(leaves)
        self.assertEqual(v["rootHash"], base64.b64encode(root).decode())
        log_key = NoteKey(ORIGIN, ED25519, LEDGER_KEY.public_key)
        witness_key = NoteKey(WITNESS, COSIGNATURE, WITNESS_KEY.public_key)
        note = LogCheckpoint(ORIGIN, 3, root).sign(log_key, LEDGER_KEY)
        self.assertEqual(v["note"], note.text())
        cosigned = note.with_signature(cosign(note.body, witness_key, WITNESS_KEY, 1760000000))
        self.assertEqual(v["cosignedNote"], cosigned.text())
        parsed = Note.parse(v["cosignedNote"])
        self.assertTrue(parsed.signed_by(log_key))
        self.assertEqual(1760000000, parsed.cosigned_by(witness_key))
        self.assertIsNone(Note.parse(v["note"]).cosigned_by(witness_key))
        self.assertFalse(Note.parse(v["note"].replace("\n3\n", "\n4\n")).signed_by(log_key))


class ScittVector(unittest.TestCase):

    def test_statements_and_receipts(self):
        v = vector("scitt.json")
        entries = chain_entries()
        leaves = [merkle.leaf_hash(bytes.fromhex(e["hash"])) for e in entries]
        root = merkle.root(leaves)
        self.assertEqual(v["rootHash"], root.hex())
        for item, entry in zip(v["items"], entries):
            index = item["sequence"] - 1
            self.assertEqual(item["statement"], EvidenceStatement.sign(entry, ISSUER, LEDGER_KEY.key_id,
                                                                       LEDGER_KEY).hex())
            self.assertEqual(item["receipt"], LogReceipt.sign(ORIGIN, LEDGER_KEY.key_id, len(entries), index,
                                                              merkle.inclusion_proof(leaves, index), root,
                                                              LEDGER_KEY).hex())
            statement = EvidenceStatement.parse(bytes.fromhex(item["statement"]))
            receipt = LogReceipt.parse(bytes.fromhex(item["receipt"]))
            self.assertTrue(statement.verify(LEDGER_KEY.public_key))
            self.assertTrue(statement.describes(EvidenceLink.of_entry(entry)))
            self.assertEqual("urn:uuid:" + entry["id"], statement.subject)
            self.assertEqual(ISSUER, statement.issuer)
            self.assertTrue(receipt.verify(statement.leaf_hash, LEDGER_KEY.public_key))
            self.assertEqual(root, receipt.root(statement.leaf_hash))
            self.assertFalse(receipt.verify(merkle.leaf_hash(b"other"), LEDGER_KEY.public_key))
            self.assertFalse(statement.verify(WITNESS_KEY.public_key))


class MandateVector(unittest.TestCase):

    def verifier(self):
        return MandateVerifier(ISSUER, keys=StaticKeys.fixed(ISSUER, LEDGER_KEY.public_key), skip_status=True,
                               clock=lambda: 1790841600 + 3600)

    def test_full_token(self):
        v = vector("mandate.json")
        check = self.verifier().verify_action(v["token"], "a2a/send", "supplier/sales")
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(v["claims"], check.claims.to_payload())

    def test_presented_with_grant_only(self):
        v = vector("mandate.json")
        check = self.verifier().verify(v["presentedWithGrantOnly"])
        self.assertTrue(check.valid, check.problems)
        payload = check.claims.to_payload()
        self.assertEqual(v["claims"]["mandate"]["grant"], payload["mandate"]["grant"])
        self.assertNotIn("principal", payload["mandate"])
        self.assertNotIn("termsHash", payload["mandate"])


class KeyBoundMandateVector(unittest.TestCase):

    def verifier(self, at):
        return MandateVerifier(ISSUER, keys=StaticKeys.fixed(ISSUER, LEDGER_KEY.public_key), skip_status=True,
                               audience="https://supplier.example", clock=lambda: at)

    def test_presentation(self):
        v = vector("mandate-key-binding.json")
        verifier = self.verifier(v["presentedAt"] + 10)
        check = verifier.verify_bound(v["presentation"], v["nonce"], "a2a/send", "supplier/orders")
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(v["claims"], check.claims.to_payload())
        self.assertEqual(PublicKey.from_base64(v["agentPublicKey"]), check.claims.holder_key)
        self.assertEqual(v["sdHash"], sd_hash(v["token"]))
        self.assertTrue(verifier.verify_bound(v["token"], v["nonce"]).has(KEY_BINDING_MISSING))
        self.assertTrue(verifier.verify_bound(v["presentation"], "00" * 32).has(KEY_BINDING_INVALID))

    def test_presentation_made_here(self):
        v = vector("mandate-key-binding.json")
        agent = PrivateKey.from_seed(bytes.fromhex(v["agentSeed"]))
        presented = present_bound(v["token"], agent, v["audience"], v["nonce"], v["presentedAt"])
        self.assertEqual(v["presentation"], presented)


class KeyHistoryVector(unittest.TestCase):

    def test_rotation_and_revocation(self):
        v = vector("key-history.json")
        rotation = KeyRotation.issue(WITNESS_KEY, LEDGER_KEY.key_id, LEDGER_KEY, v["rotation"]["activatedAt"])
        revocation = KeyRevocation.issue(LEDGER_KEY.key_id, v["revocation"]["compromisedAt"],
                                         v["revocation"]["revokedAt"], v["revocation"]["reason"], WITNESS_KEY)
        self.assertEqual(v["rotation"]["signedContent"].encode(),
                         signed_rotation_bytes(WITNESS_KEY.key_id, WITNESS_KEY.public_key.encoded, LEDGER_KEY.key_id,
                                               rotation.activated_at))
        self.assertEqual(v["rotation"]["keySignature"], rotation.key_signature)
        self.assertEqual(v["rotation"]["previousKeySignature"], rotation.previous_key_signature)
        self.assertEqual(v["revocation"]["signedContent"].encode(),
                         signed_revocation_bytes(LEDGER_KEY.key_id, revocation.compromised_at, revocation.revoked_at,
                                                 revocation.reason, WITNESS_KEY.key_id))
        self.assertEqual(v["revocation"]["signature"], revocation.signature)
        self.assertEqual(v["compromisedEpochSecond"], revocation.compromised_epoch_second)
        trusted, revoked = trusted_and_revoked(WITNESS_KEY.public_key, [LEDGER_KEY.public_key], [rotation],
                                               [revocation])
        self.assertIn(LEDGER_KEY.key_id, trusted)
        self.assertIn(LEDGER_KEY.key_id, revoked)


if __name__ == "__main__":
    unittest.main()
