import os
import unittest
import uuid
from datetime import datetime, timedelta, timezone

from nexusphere_ledger import (Denied, EvidenceLink, EvidenceStatement, LedgerClient, LedgerError, LogReceipt,
                               MandateVerifier, Note, NoteKey, PrivateKey, PublicKey, SdJwt, present_bound,
                               verify_package)

URL = os.environ.get("NEXUSPHERE_LEDGER_URL")
OPERATOR_KEY = os.environ.get("NEXUSPHERE_LEDGER_API_KEY", "nexusphere-ledger-development-key-change-me")


@unittest.skipUnless(URL, "set NEXUSPHERE_LEDGER_URL to run against a running ledger")
class LiveLedger(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.operator = LedgerClient(URL, OPERATOR_KEY)
        cls.agent_id = "sdk-agent-" + uuid.uuid4().hex[:8]
        registered = cls.operator.register_agent(cls.agent_id, "Python SDK test", "acme")
        cls.agent = LedgerClient(URL, registered["apiKey"])
        cls.grant = cls.operator.create_grant("alice", cls.agent_id, ["tools/call"], ["read_*"],
                                              datetime.now(timezone.utc) + timedelta(days=1), max_uses=10)

    def test_record_decide_export_and_verify(self):
        single = self.agent.record(self.agent_id, "alice", "tools/call", "SUCCEEDED", target="read_invoice",
                                   decision="ALLOW", input={"invoice": 7}, attributes={"sdk": "python"})
        self.assertEqual(64, len(single["hash"]))
        batch = self.agent.record_batch([
            {"agentId": self.agent_id, "principalId": "alice", "action": "tools/call", "outcome": "SUCCEEDED"},
            {"agent_id": self.agent_id, "principal_id": "alice", "action": "tools/call", "outcome": "FAILED"}])
        self.assertEqual(batch[0]["sequence"] + 1, batch[1]["sequence"])
        with self.agent.act("alice", "tools/call", "read_invoice", input="invoice 7") as action:
            action.output("contents")
        self.assertEqual("SUCCEEDED", action.reported["outcome"])
        with self.assertRaises(Denied) as denied:
            with self.agent.act("alice", "tools/call", "send_email"):
                self.fail("a denied action must not run")
        self.assertEqual("NOT_COVERED", denied.exception.decision["reasonCode"])
        with self.assertRaises(ValueError):
            with self.agent.act("alice", "tools/call", "read_mail"):
                raise ValueError("tool failed")
        self.operator.create_checkpoint()
        pinned = self.operator.active_public_key()
        pkg = self.operator.export_package(agent_id=self.agent_id)
        report = verify_package(pkg, pinned)
        self.assertTrue(report.valid, report.problems)
        self.assertGreaterEqual(report.disclosed_entries, 7)
        self.assertEqual(report.disclosed_entries, report.proven_entries)
        self.assertEqual(report.disclosed_entries, report.receipted_entries)
        changed = dict(pkg, links=[dict(link) for link in pkg["links"]])
        index = next(i for i, link in enumerate(changed["links"]) if link.get("entry"))
        changed["links"][index]["entry"] = dict(changed["links"][index]["entry"], outcome="FAILED")
        self.assertFalse(verify_package(changed, pinned).valid)
        statement = EvidenceStatement.parse(self.agent.statement(single["id"]))
        receipt = LogReceipt.parse(self.agent.receipt(single["id"]))
        key = PublicKey.from_base64(pinned)
        self.assertTrue(statement.verify(key))
        self.assertTrue(statement.describes(EvidenceLink.of_entry(single)))
        self.assertTrue(receipt.verify(statement.leaf_hash, key))
        note = Note.parse(self.agent.log_checkpoint())
        self.assertTrue(note.signed_by(NoteKey.parse(self.agent.log_key())))
        self.assertEqual(single, self.agent.evidence(single["id"]))
        ids = [entry["id"] for entry in self.agent.iter_evidence(limit=2)]
        self.assertIn(single["id"], ids)

    def test_mandate_from_live_ledger(self):
        issued = self.operator.issue_mandate(self.grant["id"], "https://supplier.example")
        token = issued["token"]
        issuer = SdJwt.parse(token).jwt.payload["iss"]
        check = MandateVerifier(issuer, audience="https://supplier.example").verify_action(token, "tools/call",
                                                                                         "read_invoice")
        self.assertTrue(check.valid, check.problems)
        self.assertEqual("alice", check.claims.principal_id)
        self.operator.revoke_mandate(issued["id"], "test")
        revoked = MandateVerifier(issuer).verify(token)
        self.assertTrue(revoked.has("REVOKED"), revoked.problems)

    def test_key_bound_mandate_from_live_ledger(self):
        agent_id = "sdk-keyed-" + uuid.uuid4().hex[:8]
        registered = self.operator.register_agent(agent_id, "Python SDK keyed agent", "acme")
        agent = LedgerClient(URL, registered["apiKey"])
        key = PrivateKey.generate()
        updated = agent.set_signing_key(agent_id, key.public_key)
        self.assertEqual(key.key_id, updated["signingKeyId"])
        grant = self.operator.create_grant("alice", agent_id, ["a2a/send"], ["supplier/*"],
                                           datetime.now(timezone.utc) + timedelta(days=1))
        token = agent.issue_mandate(grant["id"], "https://supplier.example")["token"]
        issuer = SdJwt.parse(token).jwt.payload["iss"]
        presented = present_bound(token, key, "https://supplier.example", "ab" * 32)
        check = MandateVerifier(issuer, audience="https://supplier.example").verify_bound(presented, "ab" * 32)
        self.assertTrue(check.valid, check.problems)
        self.assertEqual(key.public_key, check.claims.holder_key)
        with self.assertRaises(LedgerError) as error:
            agent.set_signing_key(agent_id, PrivateKey.generate().public_key)
        self.assertEqual(403, error.exception.status)

    def test_errors(self):
        with self.assertRaises(LedgerError) as error:
            self.agent.record(self.agent_id, None, "tools/call", "SUCCEEDED")
        self.assertEqual(400, error.exception.status)
        self.assertEqual("INVALID_REQUEST", error.exception.code)
        with self.assertRaises(LedgerError) as error:
            self.agent.export_package()
        self.assertEqual(403, error.exception.status)


if __name__ == "__main__":
    unittest.main()
