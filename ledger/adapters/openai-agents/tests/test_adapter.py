import asyncio
import unittest

from agents import Runner
from nexusphere_ledger import hash_of

from nexusphere_openai_agents import NexusphereHooks
from tests.support import FakeLedger, agent, tools


def outputs(result):
    return [item.output for item in result.new_items if type(item).__name__ == "ToolCallOutputItem"]


class RecordTest(unittest.TestCase):

    def test_records_every_tool_call_with_its_outcome(self):
        ledger = FakeLedger()
        hooks = NexusphereHooks(ledger, "invoice-agent")
        t = tools()
        runner = agent(hooks.govern([t["read_invoice"], t["broken_tool"]], decide=False),
                       [("read_invoice", {"invoice": 7}), ("broken_tool", {"value": 1})])
        asyncio.run(Runner.run(runner, "go", context={"principal_id": "alice"}, hooks=hooks))
        by_target = {entry["target"]: entry for entry in ledger.records}
        read = by_target["read_invoice"]
        self.assertEqual(("invoice-agent", "alice", "tools/call", "SUCCEEDED"),
                         (read["agentId"], read["principalId"], read["action"], read["outcome"]))
        self.assertEqual(hash_of({"invoice": 7}), read["input_hash"])
        self.assertEqual(hash_of("invoice 7: 120 EUR"), read["output_hash"])
        self.assertEqual("call-0", read["attributes"]["tool_call_id"])
        self.assertTrue(read["correlation_id"].startswith("trace_"))
        broken = by_target["broken_tool"]
        self.assertEqual(("FAILED", "RuntimeError", None), (broken["outcome"], broken["reason"], broken["output_hash"]))
        self.assertEqual([], ledger.decisions)

    def test_hooks_alone_record_and_principal_sources(self):
        ledger = FakeLedger()
        t = tools()

        class Context:
            principal_id = "carol"

        asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                               context=Context(), hooks=NexusphereHooks(ledger, "a")))
        asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                               context={"user": "dave"}, hooks=NexusphereHooks(ledger, "a", principal=lambda c: c["user"])))
        asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                               hooks=NexusphereHooks(ledger, "a", principal_id="bob")))
        asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                               hooks=NexusphereHooks(ledger, "a")))
        self.assertEqual(["carol", "dave", "bob"], [entry["principalId"] for entry in ledger.records])

    def test_ledger_errors_do_not_stop_the_agent_unless_strict(self):
        class Broken(FakeLedger):
            def record(self, *args, **kwargs):
                raise ConnectionError("ledger down")

        t = tools()
        result = asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                                        context={"principal_id": "alice"}, hooks=NexusphereHooks(Broken(), "a")))
        self.assertEqual("done", result.final_output)
        with self.assertRaisesRegex(Exception, "ledger down"):
            asyncio.run(Runner.run(agent([t["read_invoice"]], [("read_invoice", {"invoice": 1})]), "go",
                                   context={"principal_id": "alice"},
                                   hooks=NexusphereHooks(Broken(), "a", strict=True)))


class DecideTest(unittest.TestCase):

    def test_allowed_calls_run_and_denied_calls_do_not(self):
        ledger = FakeLedger()
        hooks = NexusphereHooks(ledger)
        t = tools()
        runner = agent(hooks.govern([t["read_invoice"], t["send_email"], t["broken_tool"]]),
                       [("read_invoice", {"invoice": 7}), ("send_email", {"to": "eve", "body": "x"}),
                        ("broken_tool", {"value": 1})])
        result = asyncio.run(Runner.run(runner, "go", context={"principal_id": "alice"}, hooks=hooks))
        replies = outputs(result)
        self.assertEqual("invoice 7: 120 EUR", replies[0])
        self.assertIn("Denied by the Nexusphere Ledger: NOT_COVERED", replies[1])
        self.assertEqual(3, len(ledger.decisions))
        self.assertEqual(hash_of({"invoice": 7}), ledger.decisions[0]["inputHash"])
        reported = {o["decisionId"]: o for o in ledger.outcomes}
        self.assertEqual(("SUCCEEDED", hash_of("invoice 7: 120 EUR")),
                         (reported["d0"]["outcome"], reported["d0"]["outputHash"]))
        self.assertEqual(("FAILED", "RuntimeError"), (reported["d2"]["outcome"], reported["d2"]["reason"]))
        self.assertNotIn("d1", reported)
        self.assertEqual([], ledger.records)

    def test_no_principal_and_ledger_errors_block_the_call(self):
        class Broken(FakeLedger):
            def decide(self, *args, **kwargs):
                raise ConnectionError("ledger down")

        t = tools()
        hooks = NexusphereHooks(FakeLedger())
        result = asyncio.run(Runner.run(agent(hooks.govern([t["read_invoice"]]), [("read_invoice", {"invoice": 1})]),
                                        "go", hooks=hooks))
        self.assertIn("No principal", outputs(result)[0])
        t = tools()
        hooks = NexusphereHooks(Broken(), principal_id="alice")
        result = asyncio.run(Runner.run(agent(hooks.govern([t["read_invoice"]]), [("read_invoice", {"invoice": 1})]),
                                        "go", hooks=hooks))
        self.assertIn("did not decide: ConnectionError", outputs(result)[0])


if __name__ == "__main__":
    unittest.main()
