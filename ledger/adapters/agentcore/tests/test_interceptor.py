import unittest

from nexusphere_ledger import hash_of

from nexusphere_agentcore import DENIED, Interceptor, Settings
from tests.support import FakeLedger, call, request_event, response_event, result


def interceptor(ledger, **settings):
    return Interceptor(Settings("http://ledger", "key", **settings), ledger)


def forwarded(output, request):
    return {**request, "body": output["mcp"]["transformedGatewayRequest"]["body"]}


class RecordModeTest(unittest.TestCase):

    def test_request_passes_and_response_is_recorded(self):
        ledger = FakeLedger()
        handler = interceptor(ledger)
        request = call()
        output = handler.handle(request_event(request))
        self.assertEqual({"interceptorOutputVersion": "1.0",
                          "mcp": {"transformedGatewayRequest": {"body": request["body"]}}}, output)
        answer = result()
        output = handler.handle(response_event(request, answer))
        self.assertEqual({"statusCode": 200, "body": answer}, output["mcp"]["transformedGatewayResponse"])
        [entry] = ledger.records
        self.assertEqual(("invoice-agent", "alice", "tools/call", "invoices___read_invoice", "SUCCEEDED"),
                         (entry["agentId"], entry["principalId"], entry["action"], entry["target"], entry["outcome"]))
        self.assertEqual(hash_of({"invoice": 7}), entry["input_hash"])
        self.assertEqual(hash_of(answer["result"]), entry["output_hash"])
        self.assertEqual("session-42", entry["correlation_id"])
        self.assertEqual({"gateway": "agentcore", "jsonrpc.id": "1"}, entry["attributes"])

    def test_failures_and_other_methods(self):
        ledger = FakeLedger()
        handler = interceptor(ledger)
        handler.handle(response_event(call(), result("boom", is_error=True)))
        handler.handle(response_event(call(), {"jsonrpc": "2.0", "id": 1, "error": {"code": -32602, "message": "bad"}}))
        handler.handle(response_event(call(), result(), status=502))
        listing = {"path": "/mcp", "httpMethod": "POST", "body": {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}}
        handler.handle(response_event(listing, {"jsonrpc": "2.0", "id": 2, "result": {"tools": []}}))
        self.assertEqual(["FAILED"] * 3, [entry["outcome"] for entry in ledger.records])
        self.assertEqual("JSON-RPC -32602 bad", ledger.records[1]["reason"])

    def test_identity_settings(self):
        ledger = FakeLedger()
        interceptor(ledger, agent_id="fixed-agent", principal_claim="email").handle(
            response_event(call(claims={"email": "alice@example.com"}), result()))
        interceptor(ledger, default_principal="acme").handle(response_event(call(claims={"client_id": "a"}), result()))
        interceptor(ledger).handle(response_event(call(claims={"sub": "alice"}), result()))
        no_headers = call()
        del no_headers["headers"]
        interceptor(ledger).handle(response_event(no_headers, result()))
        self.assertEqual([("fixed-agent", "alice@example.com"), ("a", "acme")],
                         [(entry["agentId"], entry["principalId"]) for entry in ledger.records])

    def test_ledger_errors_never_break_the_gateway(self):
        answer = result()
        output = interceptor(FakeLedger(fail=True)).handle(response_event(call(), answer))
        self.assertEqual(answer, output["mcp"]["transformedGatewayResponse"]["body"])


class DecideModeTest(unittest.TestCase):

    def test_allowed_call_carries_the_decision_to_the_response(self):
        ledger = FakeLedger()
        handler = interceptor(ledger, mode="decide")
        request = call()
        output = handler.handle(request_event(request))
        self.assertNotIn("transformedGatewayResponse", output["mcp"])
        body = output["mcp"]["transformedGatewayRequest"]["body"]
        self.assertEqual({"nexusphere.dev/decisionId": "d0"}, body["params"]["_meta"])
        self.assertEqual(("invoice-agent", "alice", hash_of({"invoice": 7}), "session-42"),
                         tuple(ledger.decisions[0][k] for k in ("agentId", "principalId", "inputHash",
                                                                 "correlationId")))
        handler.handle(response_event(forwarded(output, request), result()))
        self.assertEqual([{"decisionId": "d0", "outcome": "SUCCEEDED", "outputHash": hash_of(result()["result"]),
                           "reason": None}], ledger.outcomes)
        self.assertEqual([], ledger.records)

    def test_denied_call_is_answered_and_not_recorded_twice(self):
        ledger = FakeLedger()
        handler = interceptor(ledger, mode="decide")
        request = call(name="mail___send_email", request_id=5)
        output = handler.handle(request_event(request))
        response = output["mcp"]["transformedGatewayResponse"]
        self.assertEqual(200, response["statusCode"])
        self.assertEqual(5, response["body"]["id"])
        self.assertEqual(DENIED, response["body"]["error"]["code"])
        self.assertEqual({"reasonCode": "NOT_COVERED", "decisionId": "d0"}, response["body"]["error"]["data"])
        handler.handle(response_event(request, response["body"]))
        self.assertEqual(([], []), (ledger.records, ledger.outcomes))

    def test_unknown_identity_and_ledger_errors_fail_closed(self):
        output = interceptor(FakeLedger(), mode="decide").handle(request_event(call(claims={"iss": "https://idp"})))
        self.assertEqual("UNKNOWN_IDENTITY",
                         output["mcp"]["transformedGatewayResponse"]["body"]["error"]["data"]["reasonCode"])
        output = interceptor(FakeLedger(fail=True), mode="decide").handle(request_event(call()))
        self.assertEqual("LEDGER_UNAVAILABLE",
                         output["mcp"]["transformedGatewayResponse"]["body"]["error"]["data"]["reasonCode"])

    def test_response_without_the_decision_is_still_recorded(self):
        ledger = FakeLedger()
        interceptor(ledger, mode="decide").handle(response_event(call(), result()))
        self.assertEqual(1, len(ledger.records))


class SettingsTest(unittest.TestCase):

    def test_from_env(self):
        settings = Settings.from_env({"NEXUSPHERE_LEDGER_URL": "http://ledger", "NEXUSPHERE_LEDGER_API_KEY": "k",
                                      "NEXUSPHERE_MODE": "Decide", "NEXUSPHERE_AGENT_ID": "agent",
                                      "NEXUSPHERE_PRINCIPAL_CLAIM": "email"})
        self.assertEqual(("decide", "agent", "email", "client_id"),
                         (settings.mode, settings.agent_id, settings.principal_claim, settings.agent_claim))
        with self.assertRaises(ValueError):
            Settings.from_env({"NEXUSPHERE_LEDGER_URL": "u", "NEXUSPHERE_LEDGER_API_KEY": "k",
                               "NEXUSPHERE_MODE": "block"})


if __name__ == "__main__":
    unittest.main()
