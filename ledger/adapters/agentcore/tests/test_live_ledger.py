import os
import unittest
import uuid
from datetime import datetime, timedelta, timezone

from nexusphere_ledger import LedgerClient, verify_package

from nexusphere_agentcore import DENIED, Interceptor, Settings
from tests.support import call, request_event, response_event, result

URL = os.environ.get("NEXUSPHERE_LEDGER_URL")
OPERATOR_KEY = os.environ.get("NEXUSPHERE_LEDGER_API_KEY", "nexusphere-ledger-development-key-change-me")


@unittest.skipUnless(URL, "set NEXUSPHERE_LEDGER_URL to run against a running ledger")
class LiveLedger(unittest.TestCase):

    def setUp(self):
        self.operator = LedgerClient(URL, OPERATOR_KEY)
        self.agent_id = "agentcore-agent-" + uuid.uuid4().hex[:8]
        self.agent_key = self.operator.register_agent(self.agent_id, "AgentCore test", "acme")["apiKey"]
        self.operator.create_grant("alice", self.agent_id, ["tools/call"], ["invoices___read_*"],
                                   datetime.now(timezone.utc) + timedelta(days=1))
        self.claims = {"sub": "alice", "client_id": self.agent_id}

    def entries(self):
        return [entry for entry in self.operator.iter_evidence(agent_id=self.agent_id)
                if entry["action"] == "tools/call"]

    def test_record_mode_with_the_operator_key(self):
        handler = Interceptor(Settings(URL, OPERATOR_KEY))
        request = call(claims=self.claims)
        handler.handle(request_event(request))
        handler.handle(response_event(request, result()))
        [entry] = self.entries()
        self.assertEqual(("alice", "invoices___read_invoice", "SUCCEEDED", "session-42"),
                         (entry["principalId"], entry["target"], entry["outcome"], entry["correlationId"]))
        package = self.operator.export_package(agent_id=self.agent_id)
        self.assertTrue(verify_package(package, self.operator.active_public_key()).valid)

    def test_decide_mode_with_the_agent_key(self):
        handler = Interceptor(Settings(URL, self.agent_key, mode="decide", agent_id=self.agent_id))
        allowed = call(claims=self.claims)
        output = handler.handle(request_event(allowed))
        forwarded = {**allowed, "body": output["mcp"]["transformedGatewayRequest"]["body"]}
        handler.handle(response_event(forwarded, result()))
        denied = call(name="mail___send_email", claims=self.claims)
        output = handler.handle(request_event(denied))
        answer = output["mcp"]["transformedGatewayResponse"]["body"]
        self.assertEqual(DENIED, answer["error"]["code"])
        handler.handle(response_event(denied, answer))
        outcomes = {entry["target"]: (entry.get("decision"), entry["outcome"]) for entry in self.entries()}
        self.assertEqual({"invoices___read_invoice": ("ALLOW", "SUCCEEDED"), "mail___send_email": ("DENY", "DENIED")},
                         outcomes)


if __name__ == "__main__":
    unittest.main()
