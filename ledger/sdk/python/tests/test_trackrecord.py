import dataclasses
import unittest

from nexusphere_ledger.index import verify_index_proof
from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.mandate import (BAD_SIGNATURE, EXPIRED, KEY_BINDING_INVALID, KEY_BINDING_MISSING,
                                       KEY_NOT_BOUND, MALFORMED, NOT_YET_VALID, UNKNOWN_KEY, UNTRUSTED_ISSUER,
                                       WRONG_AUDIENCE, WRONG_TYPE, Jws, SdJwt, StaticKeys, present_bound)
from nexusphere_ledger.trackrecord import (TrackRecordClaims, TrackRecordVerifier, issue_track_record,
                                           match_track_record, track_record_claims)

from .support import ISSUER, LEDGER_KEY, vector

NOW = 1790841600 + 3600
AGENT = PrivateKey.generate()
AUDIENCE = "https://buyer.example"
NONCE = "ab" * 32


def claims(**changes) -> TrackRecordClaims:
    return dataclasses.replace(TrackRecordClaims.from_payload(vector("agent-track-record.json")["claims"]),
                               **changes)


def issue(key=LEDGER_KEY, **changes) -> str:
    return issue_track_record(claims(**changes), key.key_id, key)


class TrackRecordVerification(unittest.TestCase):

    def verifier(self, **options):
        return TrackRecordVerifier(ISSUER, keys=StaticKeys.fixed(ISSUER, LEDGER_KEY.public_key), clock=lambda: NOW,
                                   **options)

    def test_issued_here_is_valid(self):
        token = issue(audience=AUDIENCE)
        check = self.verifier(audience=AUDIENCE).verify(token)
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(claims(audience=AUDIENCE), check.claims)
        self.assertEqual(claims(audience=AUDIENCE), track_record_claims(token))
        presented = SdJwt.parse(token).present({"principals"})
        check = self.verifier().verify(presented)
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(1, check.claims.principals)
        self.assertIsNone(check.claims.outcomes)
        self.assertIsNone(check.claims.actions)

    def test_record_without_entries(self):
        empty = claims(total=0, first_sequence=None, last_sequence=None, first_at=None, last_at=None, outcomes={},
                       principals=0, actions={})
        check = self.verifier().verify(issue_track_record(empty, LEDGER_KEY.key_id, LEDGER_KEY))
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(empty, check.claims)
        self.assertNotIn("firstSequence", check.claims.to_payload()["record"])

    def test_time_and_audience(self):
        self.assertTrue(self.verifier().verify(issue(issued_at=NOW + 120)).has(NOT_YET_VALID))
        self.assertTrue(self.verifier().verify(issue(expires_at=NOW - 120)).has(EXPIRED))
        self.assertTrue(self.verifier(audience=AUDIENCE).verify(issue(audience="https://other.example"))
                        .has(WRONG_AUDIENCE))
        self.assertTrue(self.verifier(audience=AUDIENCE).verify(issue()).valid)

    def test_issuer_key_and_signature(self):
        other = PrivateKey.generate()
        self.assertTrue(self.verifier().verify(issue(issuer="https://evil.example")).has(UNTRUSTED_ISSUER))
        self.assertTrue(self.verifier().verify(issue(key=other)).has(UNKNOWN_KEY))
        token = issue()
        head, payload, signature = token.split("~")[0].split(".")
        forged = ".".join([head, payload, signature[:-4] + ("AAAA" if not signature.endswith("AAAA") else "BBBB")])
        self.assertTrue(self.verifier().verify(forged + token[len(token.split("~")[0]):]).has(BAD_SIGNATURE))
        mandate = vector("mandate.json")["token"]
        self.assertTrue(self.verifier().verify(mandate).has(MALFORMED))
        payload = claims().to_payload()
        typed = Jws.sign({"alg": "EdDSA", "typ": "JWT", "kid": LEDGER_KEY.key_id}, payload, LEDGER_KEY) + "~"
        self.assertTrue(self.verifier().verify(typed).has(WRONG_TYPE))
        self.assertTrue(self.verifier().verify("not-a-token").has(MALFORMED))
        with self.assertRaises(ValueError):
            claims(head_hash="00")

    def test_key_binding(self):
        bound = issue(holder_key=AGENT.public_key, audience=AUDIENCE)
        presented = present_bound(bound, AGENT, AUDIENCE, NONCE, NOW - 5)
        check = self.verifier().verify_bound(presented, NONCE)
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(AGENT.public_key, check.claims.holder_key)
        self.assertTrue(self.verifier().verify_bound(bound, NONCE).has(KEY_BINDING_MISSING))
        self.assertTrue(self.verifier().verify_bound(presented, "cd" * 32).has(KEY_BINDING_INVALID))
        stolen = present_bound(bound, PrivateKey.generate(), AUDIENCE, NONCE, NOW)
        self.assertTrue(self.verifier().verify_bound(stolen, NONCE).has(KEY_BINDING_INVALID))
        unbound = present_bound(issue(), AGENT, AUDIENCE, NONCE, NOW)
        self.assertTrue(self.verifier().verify_bound(unbound, NONCE).has(KEY_BINDING_INVALID))
        self.assertTrue(self.verifier(require_key_binding=True).verify(issue()).has(KEY_NOT_BOUND))


class TrackRecordIndexMatch(unittest.TestCase):

    def setUp(self):
        self.proof = vector("agent-track-record.json")["indexProof"]
        self.index = verify_index_proof(self.proof, LEDGER_KEY.public_key.encoded)

    def test_matches(self):
        self.assertEqual([], match_track_record(claims(), self.proof, self.index))

    def test_tampered_total(self):
        self.assertEqual(["The track record claims 3 entries but the index proves 2"],
                         match_track_record(claims(total=3), self.proof, self.index))
        self.assertEqual(["The track record claims 2 entries but the index proves 2"],
                         match_track_record(claims(last_sequence=3), self.proof, self.index))

    def test_other_agent_or_checkpoint(self):
        self.assertEqual(["The index proof is not about the agent sales-agent"],
                         match_track_record(claims(agent_id="sales-agent"), self.proof, self.index))
        other = vector("index-proof.json")["proofs"][1]
        problems = match_track_record(claims(), other, verify_index_proof(other, LEDGER_KEY.public_key.encoded))
        self.assertIn("The index proof is not about the agent invoice-agent", problems)
        self.assertEqual(["The index proof is for another checkpoint or index root than the track record"],
                         match_track_record(claims(checkpoint_sequence=4), self.proof, self.index))


if __name__ == "__main__":
    unittest.main()
