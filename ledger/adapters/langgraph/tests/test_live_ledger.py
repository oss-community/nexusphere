import os
import unittest
import uuid
from datetime import datetime, timedelta, timezone

from nexusphere_ledger import LedgerClient, verify_package

from nexusphere_langgraph import NexusphereCallbackHandler, govern
from tests.support import graph, read_invoice, send_email

URL = os.environ.get("NEXUSPHERE_LEDGER_URL")
OPERATOR_KEY = os.environ.get("NEXUSPHERE_LEDGER_API_KEY", "nexusphere-ledger-development-key-change-me")


@unittest.skipUnless(URL, "set NEXUSPHERE_LEDGER_URL to run against a running ledger")
class LiveLedger(unittest.TestCase):

    def setUp(self):
        self.operator = LedgerClient(URL, OPERATOR_KEY)
        self.agent_id = "langgraph-agent-" + uuid.uuid4().hex[:8]
        self.agent = LedgerClient(URL, self.operator.register_agent(self.agent_id, "LangGraph test", "acme")["apiKey"])
        self.operator.create_grant("alice", self.agent_id, ["tools/call"], ["read_*"],
                                   datetime.now(timezone.utc) + timedelta(days=1))

    def entries(self):
        return [entry for entry in self.operator.iter_evidence(agent_id=self.agent_id)
                if entry["action"] == "tools/call"]

    def test_callback_handler_records_and_the_package_verifies(self):
        handler = NexusphereCallbackHandler(self.agent, self.agent_id, strict=True)
        graph([read_invoice], [("read_invoice", {"invoice": 7})]).invoke(
            {"messages": []}, {"callbacks": [handler], "metadata": {"nexusphere_principal": "alice"}})
        [entry] = self.entries()
        self.assertEqual(("alice", "read_invoice", "SUCCEEDED"),
                         (entry["principalId"], entry["target"], entry["outcome"]))
        package = self.operator.export_package(agent_id=self.agent_id)
        self.assertTrue(verify_package(package, self.operator.active_public_key()).valid)

    def test_governed_tools_are_decided_by_the_ledger(self):
        tools = govern([read_invoice, send_email], self.agent, principal_id="alice")
        result = graph(tools, [("read_invoice", {"invoice": 7}), ("send_email", {"to": "eve", "body": "x"})]).invoke(
            {"messages": []})
        replies = {message.name: message.content for message in result["messages"][1:]}
        self.assertEqual("invoice 7: 120 EUR", replies["read_invoice"])
        self.assertIn("NOT_COVERED", replies["send_email"])
        outcomes = {entry["target"]: (entry.get("decision"), entry["outcome"]) for entry in self.entries()}
        self.assertEqual({"read_invoice": ("ALLOW", "SUCCEEDED"), "send_email": ("DENY", "DENIED")}, outcomes)


if __name__ == "__main__":
    unittest.main()
