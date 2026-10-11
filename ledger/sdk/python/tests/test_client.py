import io
import json
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone

from nexusphere_ledger import Denied, LedgerClient, LedgerError

MODEL = {"name": "model-a", "version": "1", "promptHash": "ab" * 32}


class Opener:

    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append(request)
        status, body = self.responses.pop(0)
        content = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        if status >= 400:
            raise urllib.error.HTTPError(request.full_url, status, "error", {}, io.BytesIO(content))
        return io.BytesIO(content)

    def body(self, index=-1):
        return json.loads(self.requests[index].data)


class Client(unittest.TestCase):

    def client(self, *responses):
        opener = Opener(*responses)
        return LedgerClient("https://ledger.example/", "key", opener=opener), opener

    def test_store_prompt(self):
        client, opener = self.client((201, {"hash": "ab" * 32}), (200, {"hash": "ab" * 32}))
        self.assertEqual("ab" * 32, client.store_prompt("Pay approved invoices only."))
        self.assertEqual("ab" * 32, client.store_prompt("Pay approved invoices only."))
        request = opener.requests[0]
        self.assertEqual("PUT", request.get_method())
        self.assertEqual("https://ledger.example/api/v1/prompts", request.full_url)
        self.assertEqual({"text": "Pay approved invoices only."}, opener.body())

    def test_prompt(self):
        stored = {"hash": "ab" * 32, "text": "Pay approved invoices only.", "createdAt": "2026-10-01T08:00:00Z",
                  "createdBy": "operator"}
        client, opener = self.client((200, stored), (404, {"code": "NOT_FOUND", "message": "No such prompt."}))
        self.assertEqual(stored, client.prompt("ab" * 32))
        self.assertEqual("GET", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/prompts/" + "ab" * 32, opener.requests[0].full_url)
        with self.assertRaises(LedgerError) as error:
            client.prompt("cd" * 32)
        self.assertEqual(404, error.exception.status)

    def test_policies(self):
        text = "permit(principal, action, resource);"
        active = {"hash": "ab" * 32, "text": text, "policies": ["policy0"], "defaultEffect": "deny",
                  "createdAt": "2026-10-01T08:00:00Z", "activatedAt": "2026-10-01T08:00:00Z"}
        stored = dict(active, activatedAt=None)
        client, opener = self.client((200, active), (200, active), (200, stored),
                                     (404, {"code": "NOT_FOUND", "message": "No policy set is active."}))
        self.assertEqual(active, client.activate_policies(text))
        self.assertEqual("PUT", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/policies", opener.requests[0].full_url)
        self.assertEqual({"text": text}, opener.body(0))
        self.assertEqual(active, client.active_policies())
        self.assertEqual("GET", opener.requests[1].get_method())
        self.assertEqual("https://ledger.example/api/v1/policies", opener.requests[1].full_url)
        self.assertEqual(stored, client.policy_set("ab" * 32))
        self.assertEqual("https://ledger.example/api/v1/policies/" + "ab" * 32, opener.requests[2].full_url)
        with self.assertRaises(LedgerError) as error:
            client.active_policies()
        self.assertEqual(404, error.exception.status)

    def test_evidence_and_decisions_carry_the_model(self):
        client, opener = self.client((201, {}), (200, {"items": []}), (201, {}), (201, {}))
        client.record("invoice-agent", "acme", "tools/call", "SUCCEEDED", model=MODEL)
        self.assertEqual(MODEL, opener.body()["model"])
        client.record_batch([{"agentId": "invoice-agent", "principalId": "acme", "action": "tools/call",
                              "outcome": "SUCCEEDED", "model": MODEL}])
        self.assertEqual(MODEL, opener.body()["items"][0]["model"])
        client.record("invoice-agent", "acme", "tools/call", "SUCCEEDED")
        self.assertNotIn("model", opener.body())
        client.decide("acme", "tools/call", "read_invoice", model=MODEL)
        self.assertEqual("https://ledger.example/api/v1/decisions", opener.requests[-1].full_url)
        self.assertEqual(MODEL, opener.body()["model"])

    def test_include_prompts(self):
        client, opener = self.client((200, {}), (200, {}), (200, {}), (200, {}), (200, b"zip"))
        client.export_package(agent_id="invoice-agent")
        self.assertNotIn("includePrompts", opener.body())
        client.export_package(agent_id="invoice-agent", include_prompts=True)
        self.assertEqual({"agentId": "invoice-agent", "includePrompts": True}, opener.body())
        client.create_report("dispute", include_prompts=True)
        self.assertEqual({"includePrompts": True}, opener.body())
        client.create_report("dispute")
        self.assertEqual({}, opener.body())
        self.assertEqual(b"zip", client.create_dispute("Invoice 7", include_prompts=True))
        self.assertEqual({"title": "Invoice 7", "includePrompts": True}, opener.body())

    def test_index_proof(self):
        proof = {"format": "nexusphere-ledger/index-proof/v1", "leaf": None}
        client, opener = self.client((200, proof), (200, proof),
                                     (403, {"code": "FORBIDDEN", "message": "Only the operator may do this."}))
        term = {"type": "grant", "delegationId": "grant-1"}
        self.assertEqual(proof, client.index_proof(term))
        self.assertEqual("POST", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/index/proofs", opener.requests[0].full_url)
        self.assertEqual({"term": term}, opener.body())
        client.index_proof(term, 7)
        self.assertEqual({"term": term, "sequence": 7}, opener.body())
        with self.assertRaises(LedgerError) as error:
            client.index_proof(term)
        self.assertEqual(403, error.exception.status)


    def test_issue_track_record(self):
        issued = {"credential": "token~", "id": "00000000-0000-4000-8000-0000000000dd",
                  "expiresAt": "2026-11-01T08:00:00Z", "checkpoint": 3}
        client, opener = self.client((200, issued), (200, issued))
        self.assertEqual(issued, client.issue_track_record("invoice-agent"))
        self.assertEqual("POST", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/agents/invoice-agent/track-record",
                         opener.requests[0].full_url)
        self.assertEqual({}, opener.body())
        client.issue_track_record("invoice-agent", "https://buyer.example", timedelta(days=7))
        self.assertEqual({"audience": "https://buyer.example", "validFor": "PT604800S"}, opener.body())

    def test_a2a_reconciliation(self):
        signed = {"statement": "a.b.c", "issuer": "https://ledger.example", "peer": "https://supplier.example",
                  "from": "2026-10-01T08:00:00Z", "to": "2026-10-02T08:00:00Z", "exchanges": 2}
        reconciled = {"reconciled": True, "ours": "https://ledger.example", "theirs": "https://supplier.example",
                      "matched": 2, "undelivered": 0, "discrepancies": [], "statement": "d.e.f",
                      "evidenceSequence": 9}
        client, opener = self.client((200, signed), (200, reconciled))
        self.assertEqual(signed, client.a2a_statement("supplier", "2026-10-01T08:00:00Z",
                                                      datetime(2026, 10, 2, 8, tzinfo=timezone.utc)))
        self.assertEqual("https://ledger.example/api/v1/a2a/statements", opener.requests[0].full_url)
        self.assertEqual({"peer": "supplier", "from": "2026-10-01T08:00:00.000000Z",
                          "to": "2026-10-02T08:00:00.000000Z"}, opener.body())
        self.assertEqual(reconciled, client.reconcile_a2a("x.y.z"))
        self.assertEqual("POST", opener.requests[1].get_method())
        self.assertEqual("https://ledger.example/api/v1/a2a/reconciliations", opener.requests[1].full_url)
        self.assertEqual({"statement": "x.y.z"}, opener.body())

    def test_approvals(self):
        pending = {"id": "00000000-0000-4000-8000-0000000000aa", "agentId": "invoice-agent", "principalId": "acme",
                   "action": "tools/call", "target": "pay_invoice", "inputHash": None, "policyHash": "ab" * 32,
                   "policies": ["policy1"], "approver": "operator", "status": "PENDING",
                   "requestedAt": "2026-10-01T08:00:00Z", "expiresAt": "2026-10-01T09:00:00Z", "resolvedAt": None,
                   "method": None, "reason": None, "evidenceId": None, "used": False}
        approved = dict(pending, status="APPROVED", method="operator", resolvedAt="2026-10-01T08:05:00Z")
        denied = dict(pending, status="DENIED", method="operator", reason="Not this month.")
        client, opener = self.client((200, {"items": [pending]}), (200, {"items": []}), (200, pending),
                                     (200, approved), (200, denied),
                                     (409, {"code": "APPROVAL_RESOLVED", "message": "The approval is resolved."}))
        self.assertEqual([pending], client.approvals())
        self.assertEqual("GET", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/approvals?limit=100", opener.requests[0].full_url)
        self.assertEqual([], client.approvals("PENDING", "invoice-agent", "acme", 5))
        self.assertEqual("https://ledger.example/api/v1/approvals?status=PENDING&agentId=invoice-agent"
                         "&principalId=acme&limit=5", opener.requests[1].full_url)
        self.assertEqual(pending, client.approval(pending["id"]))
        self.assertEqual("https://ledger.example/api/v1/approvals/" + pending["id"], opener.requests[2].full_url)
        self.assertEqual(approved, client.approve(pending["id"]))
        self.assertEqual("POST", opener.requests[3].get_method())
        self.assertEqual("https://ledger.example/api/v1/approvals/%s/approve" % pending["id"],
                         opener.requests[3].full_url)
        self.assertEqual({}, opener.body())
        self.assertEqual(denied, client.deny_approval(pending["id"], "Not this month."))
        self.assertEqual("https://ledger.example/api/v1/approvals/%s/deny" % pending["id"],
                         opener.requests[4].full_url)
        self.assertEqual({"reason": "Not this month."}, opener.body())
        with self.assertRaises(LedgerError) as error:
            client.approve(pending["id"])
        self.assertEqual(409, error.exception.status)
        self.assertEqual("APPROVAL_RESOLVED", error.exception.code)

    def test_anomalies(self):
        anomaly = {"id": "00000000-0000-4000-8000-0000000000cc", "agentId": "invoice-agent", "kind": "DENIALS",
                   "status": "OPEN", "observed": 0.5, "expected": 0.05, "score": 4.2,
                   "windowStart": "2026-10-01T07:00:00Z", "windowEnd": "2026-10-01T08:00:00Z",
                   "detectedAt": "2026-10-01T08:00:01Z", "resolvedAt": None, "evidenceId": None,
                   "resolvedEvidenceId": None}
        client, opener = self.client((200, {"items": [anomaly]}), (200, {"items": []}), (200, anomaly),
                                     (200, {"items": [anomaly]}),
                                     (404, {"code": "NOT_FOUND", "message": "The anomaly does not exist."}))
        self.assertEqual([anomaly], client.anomalies())
        self.assertEqual("GET", opener.requests[0].get_method())
        self.assertEqual("https://ledger.example/api/v1/anomalies?limit=100", opener.requests[0].full_url)
        self.assertEqual([], client.anomalies("RESOLVED", "invoice-agent", 5))
        self.assertEqual("https://ledger.example/api/v1/anomalies?status=RESOLVED&agentId=invoice-agent&limit=5",
                         opener.requests[1].full_url)
        self.assertEqual(anomaly, client.anomaly(anomaly["id"]))
        self.assertEqual("https://ledger.example/api/v1/anomalies/" + anomaly["id"], opener.requests[2].full_url)
        self.assertEqual([anomaly], client.detect_anomalies())
        self.assertEqual("POST", opener.requests[3].get_method())
        self.assertEqual("https://ledger.example/api/v1/anomalies/detect", opener.requests[3].full_url)
        self.assertEqual({}, opener.body())
        with self.assertRaises(LedgerError) as error:
            client.anomaly("missing")
        self.assertEqual(404, error.exception.status)
        self.assertEqual("NOT_FOUND", error.exception.code)

    def test_act_raises_denied_with_the_approval(self):
        decision = {"decisionId": "00000000-0000-4000-8000-0000000000bb", "decision": "DENY",
                    "reasonCode": "APPROVAL_PENDING", "reason": "The operator must approve.",
                    "approvalId": "00000000-0000-4000-8000-0000000000aa"}
        client, opener = self.client((200, decision), (200, dict(decision, reasonCode="NO_GRANT",
                                                                 approvalId=None)))
        with self.assertRaises(Denied) as error:
            with client.act("acme", "tools/call", "pay_invoice"):
                self.fail("the block must not run")
        self.assertEqual(decision, error.exception.decision)
        self.assertEqual(decision["approvalId"], error.exception.approval_id)
        self.assertEqual(1, len(opener.requests))
        with self.assertRaises(Denied) as error:
            with client.act("acme", "tools/call", "pay_invoice"):
                self.fail("the block must not run")
        self.assertIsNone(error.exception.approval_id)


if __name__ == "__main__":
    unittest.main()
