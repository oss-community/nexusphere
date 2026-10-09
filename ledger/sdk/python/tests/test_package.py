import base64
import copy
import unittest

from nexusphere_ledger import verify_package
from nexusphere_ledger.keys import KeyRevocation, KeyRotation, PrivateKey

from .support import LEDGER_KEY, entries, key_record, package, witness_note_key

PINNED = LEDGER_KEY.public_key.encoded


class PackageVerification(unittest.TestCase):

    def setUp(self):
        self.chain = entries(6)

    def test_valid_package_with_log_and_witness(self):
        pkg = package(self.chain, {2, 4}, witness=True)
        report = verify_package(pkg, PINNED, [witness_note_key().vkey])
        self.assertTrue(report.valid, report.problems)
        self.assertEqual(LEDGER_KEY.key_id, report.key_id)
        self.assertEqual(LEDGER_KEY.key_id, report.pinned_key_id)
        self.assertEqual(6, report.checked_links)
        self.assertEqual(2, report.disclosed_entries)
        self.assertEqual(2, report.proven_entries)
        self.assertEqual(2, report.receipted_entries)
        self.assertEqual(["witness.example"], report.witnesses)
        self.assertEqual("ledger.example", report.log_origin)
        self.assertEqual(6, report.log_tree_size)
        self.assertEqual("checkpointSequence", list(report.to_dict())[4])

    def test_anchor_and_scope(self):
        scope = {"agentId": "invoice-agent", "principalId": None, "fromSequence": 3, "toSequence": 5}
        report = verify_package(package(self.chain, {3, 5}, anchor=2, scope=scope), PINNED)
        self.assertTrue(report.valid, report.problems)
        self.assertEqual(2, report.anchor_sequence)
        self.assertEqual(3, report.first_sequence)
        self.assertEqual(4, report.checked_links)

    def test_entry_outside_scope(self):
        scope = {"agentId": "invoice-agent", "principalId": None, "fromSequence": 1, "toSequence": 6}
        report = verify_package(package(self.chain, {2}, scope=scope), PINNED)
        self.assertFalse(report.valid)
        self.assertIn("sequence 2: the disclosed entry is outside the scope of the package", report.problems)

    def test_changed_entry(self):
        pkg = package(self.chain, {3})
        pkg["links"][2]["entry"]["target"] = "delete_invoice"
        report = verify_package(pkg, PINNED)
        self.assertFalse(report.valid)
        self.assertIn("sequence 3: the disclosed entry does not match its content hash", report.problems)

    def test_removed_link(self):
        pkg = package(self.chain, {1})
        del pkg["links"][3]
        report = verify_package(pkg, PINNED)
        self.assertFalse(report.valid)
        self.assertIn("sequence 5: expected sequence 4 but found 5", report.problems)

    def test_forged_checkpoint(self):
        pkg = package(self.chain, {1})
        pkg["checkpoint"]["sequence"] = 5
        report = verify_package(pkg, PINNED)
        self.assertIn("the signature of checkpoint 5 is not valid", report.problems)

    def test_key_from_package_is_not_pinned(self):
        other = PrivateKey.generate()
        report = verify_package(package(self.chain, {1}, key=other), PINNED)
        self.assertFalse(report.valid)
        self.assertTrue(report.problems[0].startswith("checkpoint 6 is signed with key " + other.key_id))
        self.assertTrue(verify_package(package(self.chain, {1}, key=other)).valid)

    def test_pinned_key_reaches_rotated_key(self):
        new = PrivateKey.generate()
        rotation = KeyRotation.issue(new, LEDGER_KEY.key_id, LEDGER_KEY, "2026-10-01T08:30:00Z")
        keys = [key_record(LEDGER_KEY, "RETIRED"), key_record(new, rotation=rotation)]
        report = verify_package(package(self.chain, {1}, key=new, keys=keys), PINNED)
        self.assertTrue(report.valid, report.problems)
        self.assertEqual(new.key_id, report.key_id)
        unendorsed = KeyRotation.issue(new, LEDGER_KEY.key_id, None, "2026-10-01T08:30:00Z")
        keys = [key_record(LEDGER_KEY, "RETIRED"), key_record(new, rotation=unendorsed)]
        self.assertFalse(verify_package(package(self.chain, {1}, key=new, keys=keys), PINNED).valid)

    def test_missing_witness(self):
        report = verify_package(package(self.chain, {1}), PINNED, [witness_note_key().vkey])
        self.assertIn("the log checkpoint is cosigned by 0 of the 1 required witnesses", report.problems)

    def test_wrong_inclusion_proof(self):
        pkg = package(self.chain, {2, 4})
        pkg["log"]["proofs"][0]["hashes"] = pkg["log"]["proofs"][1]["hashes"]
        report = verify_package(pkg, PINNED)
        self.assertIn("sequence 2 is not proven to be in the log checkpoint", report.problems)

    def test_statement_for_other_entry(self):
        pkg = package(self.chain, {2, 4})
        pkg["log"]["proofs"][0]["statement"] = pkg["log"]["proofs"][1]["statement"]
        report = verify_package(pkg, PINNED)
        self.assertIn("sequence 2 has a statement for different evidence", report.problems)

    def test_unknown_format_and_nothing_disclosed(self):
        pkg = package(self.chain, set())
        self.assertIn("the package discloses no evidence", verify_package(pkg, PINNED).problems)
        pkg = copy.deepcopy(pkg)
        pkg["format"] = "other"
        self.assertEqual(["unknown package format other"], verify_package(pkg, PINNED).problems)

    def test_package_without_log(self):
        report = verify_package(package(self.chain, {1}, log=False), PINNED)
        self.assertTrue(report.valid, report.problems)
        self.assertIsNone(report.log_tree_size)

    def test_bad_signature_encoding(self):
        pkg = package(self.chain, {1})
        pkg["checkpoint"]["signature"] = base64.b64encode(b"x" * 64).decode()
        self.assertIn("the signature of checkpoint 6 is not valid", verify_package(pkg, PINNED).problems)


class RevokedKeys(unittest.TestCase):

    def setUp(self):
        self.chain = entries(4)
        self.next = PrivateKey.generate()
        self.witness = [witness_note_key().vkey]

    def key_list(self, compromised_at, revoker=None):
        revoker = revoker or self.next
        rotation = KeyRotation.issue(self.next, LEDGER_KEY.key_id, LEDGER_KEY, "2025-10-11T00:00:00Z")
        revocation = KeyRevocation.issue(LEDGER_KEY.key_id, compromised_at, "2025-10-11T00:00:00Z", "key leaked",
                                         revoker)
        revoked = key_record(LEDGER_KEY, "REVOKED")
        revoked["revocation"] = {"format": "nexusphere-ledger/key-revocation/v1",
                                 "compromisedAt": revocation.compromised_at, "revokedAt": revocation.revoked_at,
                                 "reason": revocation.reason, "revokerKeyId": revocation.revoker_key_id,
                                 "signature": revocation.signature}
        return [revoked, key_record(self.next, rotation=rotation)]

    def verify(self, compromised_at, witness=True, witnesses=None, revoker=None):
        pkg = package(self.chain, {2}, witness=witness)
        return verify_package(pkg, self.next.public_key.encoded, self.witness if witnesses is None else witnesses,
                              0, self.key_list(compromised_at, revoker))

    def test_witnesses_before_the_compromise_keep_the_package_valid(self):
        report = self.verify("2025-10-10T00:00:00Z")
        self.assertTrue(report.valid, report.problems)
        self.assertEqual([LEDGER_KEY.key_id], report.revoked_keys)
        self.assertEqual("revokedKeys", list(report.to_dict())[16])

    def test_witnesses_after_the_compromise_do_not(self):
        report = self.verify("2025-10-01T00:00:00Z")
        self.assertFalse(report.valid)
        self.assertIn("key %s was revoked as compromised from 2025-10-01T00:00:00Z, and 0 of the 1 required witness "
                      "cosignatures prove that the log checkpoint was made before then" % LEDGER_KEY.key_id,
                      report.problems)

    def test_no_witness_means_no_proof(self):
        self.assertFalse(self.verify("2025-10-10T00:00:00Z", witness=False).valid)
        self.assertFalse(self.verify("2025-10-10T00:00:00Z", witnesses=[]).valid)

    def test_an_untrusted_revoker_is_ignored(self):
        report = self.verify("2025-10-01T00:00:00Z", revoker=PrivateKey.generate())
        self.assertTrue(report.valid, report.problems)
        self.assertEqual([], report.revoked_keys)

    def test_a_key_endorsed_after_the_compromise_is_not_reached(self):
        keys = self.key_list("2025-10-01T00:00:00Z")
        pkg = package(self.chain, {2}, key=self.next, keys=[keys[0]])
        report = verify_package(pkg, PINNED, [], 0, keys)
        self.assertFalse(report.valid)
        self.assertTrue(any("does not reach" in problem for problem in report.problems), report.problems)

    def test_a_key_cannot_revoke_itself(self):
        with self.assertRaises(ValueError):
            KeyRevocation.issue(LEDGER_KEY.key_id, "2025-10-01T00:00:00Z", "2025-10-01T00:00:00Z", "x", LEDGER_KEY)


if __name__ == "__main__":
    unittest.main()
