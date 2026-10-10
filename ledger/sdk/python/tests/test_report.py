import unittest

from nexusphere_ledger import verify_report
from nexusphere_ledger.keys import PrivateKey

from .support import LEDGER_KEY, entries, package, report, sign_report

PINNED = LEDGER_KEY.public_key.encoded


class ReportVerification(unittest.TestCase):

    def setUp(self):
        self.chain = entries(4)
        self.pkg = package(self.chain, {2})

    def test_a_report_signed_by_the_ledger_for_the_package_is_valid(self):
        check = verify_report(report(self.pkg), self.pkg, PINNED)
        self.assertEqual([], check.problems)
        self.assertTrue(check.valid)
        self.assertEqual(1, check.entries)
        self.assertEqual({"SHOWN": 1}, check.statuses)
        self.assertEqual(["valid", "report", "title", "keyId", "checkpointSequence", "entries", "statuses",
                          "problems"], list(check.to_dict()))

    def test_a_changed_finding_breaks_the_signature(self):
        signed = report(self.pkg)
        signed["sections"][0]["status"] = "NOT_SHOWN"
        self.assertEqual(["the signature of the report is not valid"],
                         verify_report(signed, self.pkg, PINNED).problems)

    def test_a_report_for_other_entries_is_rejected(self):
        problems = verify_report(report(self.pkg), package(self.chain, {3}), PINNED).problems
        self.assertIn("the report covers other entries than the package discloses", problems)
        self.assertNotIn("the report is for another checkpoint than the package", problems)
        problems = verify_report(report(self.pkg), package(entries(5), {3}), PINNED).problems
        self.assertIn("the report covers other entries than the package discloses", problems)
        self.assertIn("the report is for another checkpoint than the package", problems)
        self.assertIn("the report has another toSequence than the package", problems)

    def test_a_report_signed_by_another_key_is_rejected(self):
        problems = verify_report(report(self.pkg, PrivateKey.generate()), self.pkg, PINNED).problems
        self.assertEqual(1, len(problems))
        self.assertIn("which the package does not trust", problems[0])

    def test_an_unknown_status_is_rejected(self):
        signed = report(self.pkg)
        signed["sections"][0]["status"] = "COMPLIANT"
        self.assertEqual(["section timeline has an unknown status COMPLIANT"],
                         verify_report(sign_report(signed), self.pkg, PINNED).problems)

    def test_a_revoked_key_and_unreadable_reports_are_rejected(self):
        keys = [dict(self.pkg["keys"][0], revocation={
            "compromisedAt": "2026-10-02T00:00:00Z", "revokedAt": "2026-10-02T00:00:00Z", "reason": "key leaked",
            "revokerKeyId": "unknown", "signature": ""})]
        self.assertEqual(["the report is signed with revoked key %s" % LEDGER_KEY.key_id],
                         verify_report(report(self.pkg), self.pkg, None, keys).problems)
        signed = report(self.pkg)
        signed["score"] = 0.5
        self.assertEqual(["the report cannot be read: reports hold only strings, integers, booleans, arrays and "
                          "objects"], verify_report(signed, self.pkg, PINNED).problems)
        self.assertEqual(["unknown report format null"], verify_report({}, self.pkg, PINNED).problems)


if __name__ == "__main__":
    unittest.main()
