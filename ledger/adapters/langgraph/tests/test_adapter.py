import asyncio
import unittest

from nexusphere_ledger import hash_of

from nexusphere_langgraph import NexusphereCallbackHandler, govern
from tests.support import FakeLedger, broken_tool, graph, read_invoice, send_email


class CallbackHandlerTest(unittest.TestCase):

    def test_records_every_tool_call_with_its_outcome(self):
        ledger = FakeLedger()
        handler = NexusphereCallbackHandler(ledger, "invoice-agent")
        app = graph([read_invoice, broken_tool], [("read_invoice", {"invoice": 7}), ("broken_tool", {"value": 1})])
        app.invoke({"messages": []}, {"callbacks": [handler], "metadata": {"nexusphere_principal": "alice"},
                                      "configurable": {"thread_id": "thread-1"}})
        by_target = {entry["target"]: entry for entry in ledger.records}
        self.assertEqual({"read_invoice", "broken_tool"}, set(by_target))
        read = by_target["read_invoice"]
        self.assertEqual(("invoice-agent", "alice", "tools/call", "SUCCEEDED"),
                         (read["agentId"], read["principalId"], read["action"], read["outcome"]))
        self.assertEqual(hash_of({"invoice": 7}), read["input_hash"])
        self.assertEqual(hash_of("invoice 7: 120 EUR"), read["output_hash"])
        self.assertEqual("thread-1", read["correlation_id"])
        self.assertEqual("call-0", read["attributes"]["tool_call_id"])
        self.assertEqual("FAILED", by_target["broken_tool"]["outcome"])

    def test_default_principal_and_missing_principal(self):
        ledger = FakeLedger()
        app = graph([read_invoice], [("read_invoice", {"invoice": 1})])
        app.invoke({"messages": []}, {"callbacks": [NexusphereCallbackHandler(ledger, "a", principal_id="bob")]})
        self.assertEqual("bob", ledger.records[0]["principalId"])
        app.invoke({"messages": []}, {"callbacks": [NexusphereCallbackHandler(ledger, "a")]})
        self.assertEqual(1, len(ledger.records))

    def test_ledger_errors_do_not_stop_the_agent_unless_strict(self):
        class Broken(FakeLedger):
            def record(self, *args, **kwargs):
                raise ConnectionError("ledger down")

        app = graph([read_invoice], [("read_invoice", {"invoice": 1})])
        config = {"metadata": {"nexusphere_principal": "alice"}}
        result = app.invoke({"messages": []}, {**config, "callbacks": [NexusphereCallbackHandler(Broken(), "a")]})
        self.assertIn("120 EUR", result["messages"][-1].content)
        result = app.invoke({"messages": []},
                            {**config, "callbacks": [NexusphereCallbackHandler(Broken(), "a", strict=True)]})
        self.assertEqual("error", result["messages"][-1].status)
        self.assertIn("ledger down", result["messages"][-1].content)


class GovernTest(unittest.TestCase):

    def test_allowed_calls_run_and_denied_calls_do_not(self):
        ledger = FakeLedger()
        tools = govern([read_invoice, send_email], ledger, principal_key="principal")
        app = graph(tools, [("read_invoice", {"invoice": 7}), ("send_email", {"to": "eve", "body": "x"})])
        result = app.invoke({"messages": []}, {"configurable": {"principal": "alice", "thread_id": "t-9"}})
        contents = {message.name: message for message in result["messages"][1:]}
        self.assertEqual("invoice 7: 120 EUR", contents["read_invoice"].content)
        self.assertIn("Denied by the Nexusphere Ledger: NOT_COVERED", contents["send_email"].content)
        self.assertEqual(["alice", "alice"], [d["principalId"] for d in ledger.decisions])
        self.assertEqual("t-9", ledger.decisions[0]["correlationId"])
        self.assertEqual(hash_of({"invoice": 7}), next(d for d in ledger.decisions
                                                       if d["target"] == "read_invoice")["inputHash"])
        self.assertEqual(1, len(ledger.outcomes))
        self.assertEqual(("SUCCEEDED", hash_of("invoice 7: 120 EUR")),
                         (ledger.outcomes[0]["outcome"], ledger.outcomes[0]["outputHash"]))

    def test_failures_are_reported_and_async_works(self):
        ledger = FakeLedger()
        app = graph(govern([broken_tool, read_invoice], ledger, principal_id="alice"),
                    [("broken_tool", {"value": 1}), ("read_invoice", {"invoice": 2})])
        asyncio.run(app.ainvoke({"messages": []}))
        self.assertEqual({"FAILED", "SUCCEEDED"}, {o["outcome"] for o in ledger.outcomes})
        self.assertEqual("RuntimeError", next(o for o in ledger.outcomes if o["outcome"] == "FAILED")["reason"])

    def test_no_principal_is_an_error_for_the_model(self):
        ledger = FakeLedger()
        result = graph([govern(read_invoice, ledger)], [("read_invoice", {"invoice": 1})]).invoke({"messages": []})
        self.assertIn("No principal", result["messages"][-1].content)
        self.assertEqual([], ledger.decisions)


if __name__ == "__main__":
    unittest.main()
