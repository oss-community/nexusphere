from typing import Annotated, List, TypedDict

from langchain_core.messages import AIMessage, AnyMessage
from langchain_core.tools import tool
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode


@tool
def read_invoice(invoice: int) -> str:
    """Read an invoice."""
    return "invoice %d: 120 EUR" % invoice


@tool
def send_email(to: str, body: str) -> str:
    """Send an email."""
    return "sent to " + to


@tool
def broken_tool(value: int) -> str:
    """Always fails."""
    raise RuntimeError("broken")


class State(TypedDict):
    messages: Annotated[List[AnyMessage], add_messages]


def graph(tools, calls):
    def model(state: State):
        return {"messages": [AIMessage(content="", tool_calls=[
            {"name": name, "args": args, "id": "call-%d" % index, "type": "tool_call"}
            for index, (name, args) in enumerate(calls)])]}

    builder = StateGraph(State)
    builder.add_node("model", model)
    builder.add_node("tools", ToolNode(tools, handle_tool_errors=True))
    builder.add_edge(START, "model")
    builder.add_edge("model", "tools")
    builder.add_edge("tools", END)
    return builder.compile()


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
