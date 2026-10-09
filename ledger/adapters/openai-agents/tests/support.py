from agents import Agent, function_tool
from agents.testing import ScriptedModel, assistant_message, function_call


def tools():
    @function_tool
    def read_invoice(invoice: int) -> str:
        """Read an invoice."""
        return "invoice %d: 120 EUR" % invoice

    @function_tool
    def send_email(to: str, body: str) -> str:
        """Send an email."""
        return "sent to " + to

    @function_tool
    def broken_tool(value: int) -> str:
        """Always fails."""
        raise RuntimeError("broken")

    return {"read_invoice": read_invoice, "send_email": send_email, "broken_tool": broken_tool}


def agent(tools, calls):
    model = ScriptedModel([
        [function_call(name, args, call_id="call-%d" % index) for index, (name, args) in enumerate(calls)],
        [assistant_message("done")]])
    return Agent(name="invoice-agent", instructions="Handle invoices.", model=model, tools=list(tools))


class FakeLedger:

    def __init__(self, allow=("read_invoice", "broken_tool")):
        self.allow = allow
        self.records = []
        self.decisions = []
        self.outcomes = []

    def record(self, agent_id, principal_id, action, outcome, **fields):
        entry = {"agentId": agent_id, "principalId": principal_id, "action": action, "outcome": outcome, **fields}
        self.records.append(entry)
        return entry

    def decide(self, principal_id, action, target=None, input_hash=None, correlation_id=None, attributes=None,
               agent_id=None):
        allowed = target in self.allow
        decision = {"decisionId": "d%d" % len(self.decisions), "decision": "ALLOW" if allowed else "DENY",
                    "reasonCode": "GRANTED" if allowed else "NOT_COVERED", "principalId": principal_id,
                    "target": target, "inputHash": input_hash, "correlationId": correlation_id}
        self.decisions.append(decision)
        return decision

    def report_outcome(self, decision_id, outcome, output_hash=None, reason=None, attributes=None):
        reported = {"decisionId": decision_id, "outcome": outcome, "outputHash": output_hash, "reason": reason}
        self.outcomes.append(reported)
        return reported
