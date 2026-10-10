import copy
import unittest

from nexusphere_ledger import GENESIS, verify_index_proof
from nexusphere_ledger.keys import PrivateKey

from .support import LEDGER_KEY, vector

PINNED = LEDGER_KEY.public_key.encoded


def proof(index):
    return copy.deepcopy(vector("index-proof.json")["proofs"][index])


class IndexProofVerification(unittest.TestCase):

    def test_a_proof_shows_how_often_a_term_occurs_or_that_it_never_did(self):
        present = verify_index_proof(proof(0), PINNED)
        absent = verify_index_proof(proof(2), PINNED)
        self.assertEqual([], present.problems)
        self.assertTrue(present.present)
        self.assertEqual(2, present.count)
        self.assertEqual(2, present.last_sequence)
        self.assertEqual([], absent.problems)
        self.assertFalse(absent.present)
        self.assertEqual(3, absent.sequence)
        self.assertEqual(["valid", "term", "present", "count", "firstSequence", "lastSequence", "sequence",
                          "headHash", "keyId", "problems"], list(present.to_dict()))

    def test_a_changed_leaf_or_term_does_not_lead_to_the_signed_root(self):
        fewer = proof(0)
        fewer["leaf"]["count"] = 1
        hidden = proof(0)
        hidden["leaf"] = None
        other = proof(0)
        other["term"]["agentId"] = "sales-agent"
        self.assertEqual(["the proof does not lead to the signed root"], verify_index_proof(fewer, PINNED).problems)
        self.assertEqual(["the proof does not lead to the signed root"], verify_index_proof(hidden, PINNED).problems)
        self.assertIn("the key does not belong to the term", verify_index_proof(other, PINNED).problems)

    def test_the_index_must_be_signed_by_a_trusted_key_and_match_the_package(self):
        signed = proof(3)
        problems = verify_index_proof(signed, PrivateKey.generate().public_key.encoded).problems
        self.assertTrue(any("which the proof does not trust" in p for p in problems), problems)
        pkg = {"checkpoint": {"sequence": 2, "headHash": GENESIS}}
        self.assertEqual(["the index is for another checkpoint than the package"],
                         verify_index_proof(signed, PINNED, package=pkg).problems)
        pkg = {"checkpoint": {"sequence": 3, "headHash": signed["index"]["headHash"]}}
        self.assertEqual([], verify_index_proof(signed, PINNED, package=pkg).problems)
        signed["index"]["root"] = GENESIS
        self.assertEqual(["the signature of the index is not valid", "the proof does not lead to the signed root"],
                         verify_index_proof(signed, PINNED).problems)
        signed["format"] = "other"
        self.assertEqual(["unknown index proof format other"], verify_index_proof(signed, PINNED).problems)

    def test_an_unknown_term_is_refused(self):
        unknown = proof(0)
        unknown["term"] = {"type": "principal", "principalId": "alice"}
        self.assertEqual(["the term is not valid: type must be one of agent, action, input or grant"],
                         verify_index_proof(unknown, PINNED).problems)
        extra = proof(0)
        extra["term"]["action"] = "tools/call"
        self.assertEqual(["the term is not valid: a agent term has only type and agentId"],
                         verify_index_proof(extra, PINNED).problems)
        empty = proof(1)
        empty["term"]["action"] = ""
        self.assertEqual(["the term is not valid: action is required for a action term"],
                         verify_index_proof(empty, PINNED).problems)

    def test_a_revoked_key_is_refused(self):
        signed = proof(0)
        keys = [dict(signed["keys"][0], revocation={
            "compromisedAt": "2026-10-02T00:00:00Z", "revokedAt": "2026-10-02T00:00:00Z", "reason": "key leaked",
            "revokerKeyId": "unknown", "signature": ""})]
        self.assertEqual(["the index is signed with revoked key %s" % LEDGER_KEY.key_id],
                         verify_index_proof(signed, None, keys).problems)
        self.assertEqual([], verify_index_proof(signed).problems)

    def test_unreadable_indexes_leaves_and_paths_are_refused(self):
        unsigned = proof(0)
        del unsigned["index"]["format"]
        self.assertEqual(["the index cannot be read: unknown index format null"],
                         verify_index_proof(unsigned, PINNED).problems)
        short = proof(0)
        short["index"]["headHash"] = "ab"
        self.assertEqual(["the index cannot be read: headHash and root must be lowercase hex SHA-256"],
                         verify_index_proof(short, PINNED).problems)
        late = proof(0)
        late["leaf"]["lastSequence"] = 4
        self.assertEqual(["the proof cannot be read: the leaf is not a valid count with its first and last sequence"],
                         verify_index_proof(late, PINNED).problems)
        named = proof(0)
        named["otherLeaf"] = proof(2)["otherLeaf"]
        self.assertEqual(["the proof cannot be read: a proof of a leaf cannot name another leaf"],
                         verify_index_proof(named, PINNED).problems)
        same = proof(2)
        same["otherLeaf"]["key"] = same["key"]
        self.assertEqual(["the proof cannot be read: the other leaf of a proof of absence has the same key"],
                         verify_index_proof(same, PINNED).problems)
        astray = proof(2)
        astray["otherLeaf"]["key"] = format(int(astray["otherLeaf"]["key"], 16) ^ (1 << 255), "064x")
        self.assertEqual(["the proof cannot be read: the other leaf is not on the path of the key"],
                         verify_index_proof(astray, PINNED).problems)
        upper = proof(0)
        upper["siblings"][0] = upper["siblings"][0].upper()
        self.assertEqual(["the proof cannot be read: a hash must be a lowercase hex SHA-256"],
                         verify_index_proof(upper, PINNED).problems)
        long = proof(0)
        long["siblings"] = long["siblings"] * 86
        self.assertEqual(["the proof cannot be read: a proof has at most 256 siblings"],
                         verify_index_proof(long, PINNED).problems)
        listed = proof(0)
        listed["leaf"] = [2, 1, 2]
        self.assertEqual(["the proof cannot be read: the leaf must be an object or null"],
                         verify_index_proof(listed, PINNED).problems)


if __name__ == "__main__":
    unittest.main()
