import asyncio
import os
import unittest
import uuid
from datetime import datetime, timedelta, timezone

from agents import Runner
from nexusphere_ledger import LedgerClient, verify_package

from nexusphere_openai_agents import NexusphereHooks
from tests.support import agent, tools

URL = os.environ.get("NEXUSPHERE_LEDGER_URL")
OPERATOR_KEY = os.environ.get("NEXUSPHERE_LEDGER_API_KEY", "nexusphere-ledger-development-key-change-me")


@unittest.skipUnless(URL, "set NEXUSPHERE_LEDGER_URL to run against a running ledger")
class LiveLedger(unittest.TestCase):

    def setUp(self):
        self.operator = LedgerClient(URL, OPERATOR_KEY)
        self.agent_id = "openai-agent-" + uuid.uuid4().hex[:8]
        self.agent = LedgerClient(URL, self.operator.register_agent(self.agent_id, "Agents SDK test", "acme")["apiKey"])
        self.operator.create_grant("alice", self.agent_id, ["tools/call"], ["read_*"],
                                   datetime.now(timezone.utc) + timedelta(days=1))

    def entries(self):
        return [entry for entry in self.operator.iter_evidence(agent_id=self.agent_id)
                if entry["action"] == "tools/call"]

    def test_hooks_record_and_the_package_verifies(self):
        hooks = NexusphereHooks(self.agent, self.agent_id, strict=True)
        t = tools()
        asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 7})]), "go",
                               context={"principal_id": "alice"}, hooks=hooks))
        [entry] = self.entries()
        self.assertEqual(("alice", "read_invoice", "SUCCEEDED"),
                         (entry["principalId"], entry["target"], entry["outcome"]))
        package = self.operator.export_package(agent_id=self.agent_id)
        self.assertTrue(verify_package(package, self.operator.active_public_key()).valid)

    def test_governed_tools_are_decided_by_the_ledger(self):
        hooks = NexusphereHooks(self.agent, principal_id="alice", strict=True)
        t = tools()
        runner = agent(hooks.govern([t["read_invoice"], t["send_email"]]),
                       [("read_invoice", {"invoice": 7}), ("send_email", {"to": "eve", "body": "x"})])
        asyncio.run(Runner.run(runner, "go", hooks=hooks))
        outcomes = {entry["target"]: (entry.get("decision"), entry["outcome"]) for entry in self.entries()}
        self.assertEqual({"read_invoice": ("ALLOW", "SUCCEEDED"), "send_email": ("DENY", "DENIED")}, outcomes)


if __name__ == "__main__":
    unittest.main()
