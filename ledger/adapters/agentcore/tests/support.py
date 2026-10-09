import base64
import json


def token(**claims):
    def part(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=").decode()

    return "Bearer %s.%s.signature" % (part({"alg": "RS256", "typ": "JWT"}), part(claims))


def call(name="invoices___read_invoice", arguments=None, claims=None, request_id=1, meta=None):
    params = {"name": name, "arguments": arguments if arguments is not None else {"invoice": 7}}
    if meta:
        params["_meta"] = meta
    body = {"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": params}
    return {"path": "/mcp", "httpMethod": "POST",
            "headers": {"Accept": "application/json",
                        "Authorization": token(**(claims or {"sub": "alice", "client_id": "invoice-agent"})),
                        "Mcp-Session-Id": "session-42"},
            "body": body}


def request_event(request):
    return {"interceptorInputVersion": "1.0",
            "mcp": {"rawGatewayRequest": {"body": json.dumps(request["body"])}, "gatewayRequest": request}}


def response_event(request, body, status=200):
    event = request_event(request)
    event["mcp"]["gatewayResponse"] = {"statusCode": status, "headers": {"Mcp-Session-Id": "session-42"},
                                       "body": body}
    return event


def result(text="invoice 7: 120 EUR", is_error=False):
    return {"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": text}], "isError": is_error}}


class FakeLedger:

    def __init__(self, allow=("invoices___read_invoice",), fail=False):
        self.allow = allow
        self.fail = fail
        self.records = []
        self.decisions = []
        self.outcomes = []

    def record(self, agent_id, principal_id, action, outcome, **fields):
        if self.fail:
            raise ConnectionError("ledger down")
        entry = {"agentId": agent_id, "principalId": principal_id, "action": action, "outcome": outcome, **fields}
        self.records.append(entry)
        return entry

    def decide(self, principal_id, action, target=None, input_hash=None, correlation_id=None, attributes=None,
               agent_id=None):
        if self.fail:
            raise ConnectionError("ledger down")
        allowed = target in self.allow
        decision = {"decisionId": "d%d" % len(self.decisions), "decision": "ALLOW" if allowed else "DENY",
                    "reasonCode": "GRANTED" if allowed else "NOT_COVERED",
                    "reason": "granted" if allowed else "no grant covers the call", "agentId": agent_id,
                    "principalId": principal_id, "target": target, "inputHash": input_hash,
                    "correlationId": correlation_id}
        self.decisions.append(decision)
        return decision

    def report_outcome(self, decision_id, outcome, output_hash=None, reason=None, attributes=None):
        reported = {"decisionId": decision_id, "outcome": outcome, "outputHash": output_hash, "reason": reason}
        self.outcomes.append(reported)
        return reported
