import asyncio
import inspect
import json
import logging
import threading
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Iterable, List, Optional, Union

from agents import FunctionTool, RunHooks, ToolGuardrailFunctionOutput, ToolInputGuardrail
from agents.tool import resolve_function_tool_failure_error_function, set_function_tool_failure_error_function
from agents.tracing import get_current_trace
from nexusphere_ledger import LedgerClient, hash_of

ACTION = "tools/call"
logger = logging.getLogger("nexusphere_openai_agents")


class NexusphereHooks(RunHooks):

    def __init__(self, client: LedgerClient, agent_id: Optional[str] = None, principal_id: Optional[str] = None,
                 principal: Optional[Callable[[Any], Optional[str]]] = None,
                 attributes: Optional[Dict[str, str]] = None, strict: bool = False):
        self.client = client
        self.agent_id = agent_id
        self.principal_id = principal_id
        self.principal = principal
        self.attributes = {"framework": "openai-agents", **(attributes or {})}
        self.strict = strict
        self.recorded: List[dict] = []
        self.guardrail = ToolInputGuardrail(guardrail_function=self._decide, name="nexusphere_ledger")
        self._calls: Dict[str, dict] = {}
        self._failures: Dict[str, str] = {}
        self._lock = threading.Lock()

    def govern(self, tools: Union[FunctionTool, Iterable[FunctionTool]], decide: bool = True):
        if isinstance(tools, FunctionTool):
            return self._govern(tools, decide)
        return [self._govern(tool, decide) for tool in tools]

    async def on_tool_start(self, context, agent, tool) -> None:
        call_id = getattr(context, "tool_call_id", None)
        if call_id is None:
            return
        with self._lock:
            call = self._calls.setdefault(call_id, {})
        call.update(occurred_at=datetime.now(timezone.utc), target=getattr(tool, "name", None) or "tool",
                    input_hash=hash_of(_arguments(getattr(context, "tool_arguments", ""))),
                    principal=self._principal_of(context), correlation=_trace_id())

    async def on_tool_end(self, context, agent, tool, result) -> None:
        call_id = getattr(context, "tool_call_id", None)
        with self._lock:
            call = self._calls.pop(call_id, None)
            error = self._failures.pop(call_id, None)
        if call is None or "target" not in call:
            return
        outcome = "FAILED" if error else "SUCCEEDED"
        output = None if error else hash_of(_content(result))
        attributes = {**self.attributes, "tool_call_id": str(call_id)[:1024]}
        try:
            if "decision" in call:
                await asyncio.to_thread(self.client.report_outcome, call["decision"]["decisionId"], outcome, output,
                                        error, attributes)
            elif call["principal"]:
                self.recorded.append(await asyncio.to_thread(
                    self.client.record, self.agent_id, call["principal"], ACTION, outcome,
                    occurred_at=call["occurred_at"], target=call["target"][:300], reason=error,
                    input_hash=call["input_hash"], output_hash=output, correlation_id=call["correlation"],
                    attributes=attributes))
            else:
                logger.warning("tool call %s not recorded: no principal", call["target"])
        except Exception:
            if self.strict:
                raise
            logger.exception("tool call %s not recorded", call["target"])

    async def _decide(self, data) -> ToolGuardrailFunctionOutput:
        context = data.context
        principal = self._principal_of(context)
        if not principal:
            return ToolGuardrailFunctionOutput.reject_content("No principal for the Nexusphere Ledger in the run "
                                                              "context")
        arguments = _arguments(context.tool_arguments)
        try:
            decision = await asyncio.to_thread(self.client.decide, principal, ACTION, context.tool_name,
                                               hash_of(arguments), _trace_id(),
                                               {**self.attributes, "tool_call_id": str(context.tool_call_id)[:1024]},
                                               self.agent_id)
        except Exception as e:
            logger.exception("no decision for tool call %s", context.tool_name)
            return ToolGuardrailFunctionOutput.reject_content("The Nexusphere Ledger did not decide: "
                                                              + type(e).__name__)
        if decision.get("decision") != "ALLOW":
            return ToolGuardrailFunctionOutput.reject_content("Denied by the Nexusphere Ledger: %s %s" % (
                decision.get("reasonCode"), decision.get("reason") or ""), output_info=decision)
        with self._lock:
            self._calls[context.tool_call_id] = {"decision": decision}
        return ToolGuardrailFunctionOutput.allow(output_info=decision)

    def _govern(self, tool: FunctionTool, decide: bool) -> FunctionTool:
        original = resolve_function_tool_failure_error_function(tool)
        failures = self._failures
        lock = self._lock

        async def failure(context, error: Exception):
            call_id = getattr(context, "tool_call_id", None)
            if call_id is not None:
                with lock:
                    failures[call_id] = type(error).__name__
            if original is None:
                raise error
            result = original(context, error)
            return await result if inspect.isawaitable(result) else result

        set_function_tool_failure_error_function(tool, failure)
        if decide:
            guardrails = [g for g in (tool.tool_input_guardrails or []) if g is not self.guardrail]
            tool.tool_input_guardrails = [self.guardrail, *guardrails]
        return tool

    def _principal_of(self, context) -> Optional[str]:
        value = getattr(context, "context", None)
        if self.principal is not None:
            found = self.principal(value)
        elif isinstance(value, dict):
            found = value.get("principal_id")
        else:
            found = getattr(value, "principal_id", None)
        return str(found) if found else self.principal_id


def _arguments(raw: str):
    try:
        return json.loads(raw) if raw else {}
    except ValueError:
        return raw


def _content(result):
    if isinstance(result, (str, dict, list)):
        return result
    try:
        return json.dumps(result, default=str, sort_keys=True)
    except (TypeError, ValueError):
        return str(result)


def _trace_id() -> Optional[str]:
    trace = get_current_trace()
    trace_id = getattr(trace, "trace_id", None) if trace is not None else None
    return trace_id[:128] if trace_id else None
