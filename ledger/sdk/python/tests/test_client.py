import io
import json
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone

from nexusphere_ledger import LedgerClient, LedgerError

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


if __name__ == "__main__":
    unittest.main()
