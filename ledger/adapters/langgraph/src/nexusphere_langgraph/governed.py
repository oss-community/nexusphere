import asyncio
from typing import Dict, Iterable, List, Optional, Union

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import BaseTool, StructuredTool, ToolException
from nexusphere_ledger import LedgerClient, hash_of

from ._common import ACTION, PRINCIPAL_KEY, correlation_of, failed, output_hash, principal_of


def govern(tools: Union[BaseTool, Iterable[BaseTool]], client: LedgerClient, agent_id: Optional[str] = None,
           principal_id: Optional[str] = None, principal_key: str = PRINCIPAL_KEY,
           attributes: Optional[Dict[str, str]] = None) -> Union[StructuredTool, List[StructuredTool]]:
    if isinstance(tools, BaseTool):
        return _govern(tools, client, agent_id, principal_id, principal_key, attributes)
    return [_govern(tool, client, agent_id, principal_id, principal_key, attributes) for tool in tools]


def _govern(tool: BaseTool, client: LedgerClient, agent_id: Optional[str], principal_id: Optional[str],
            principal_key: str, attributes: Optional[Dict[str, str]]) -> StructuredTool:
    extra = {"framework": "langgraph", **(attributes or {})}

    def decide(config: RunnableConfig, arguments: dict) -> dict:
        metadata = {**(config.get("configurable") or {}), **(config.get("metadata") or {})}
        principal = principal_of(metadata, principal_key, principal_id)
        if not principal:
            raise ToolException("No principal for the Nexusphere Ledger in the run metadata key " + principal_key)
        decision = client.decide(principal, ACTION, tool.name, hash_of(arguments),
                                 correlation_of(metadata, None), extra, agent_id)
        if decision.get("decision") != "ALLOW":
            raise ToolException("Denied by the Nexusphere Ledger: %s %s"
                                % (decision.get("reasonCode"), decision.get("reason") or ""))
        return decision

    def report(decision: dict, result=None, error: Optional[BaseException] = None) -> None:
        if error is not None:
            client.report_outcome(decision["decisionId"], "FAILED", None, type(error).__name__, extra)
        elif failed(result):
            client.report_outcome(decision["decisionId"], "FAILED", output_hash(result), "tool returned an error",
                                  extra)
        else:
            client.report_outcome(decision["decisionId"], "SUCCEEDED", output_hash(result), None, extra)

    def run(config: RunnableConfig, **arguments):
        decision = decide(config, arguments)
        try:
            result = tool.invoke(arguments, config)
        except BaseException as e:
            report(decision, error=e)
            raise
        report(decision, result)
        return result

    async def arun(config: RunnableConfig, **arguments):
        decision = await asyncio.to_thread(decide, config, arguments)
        try:
            result = await tool.ainvoke(arguments, config)
        except BaseException as e:
            await asyncio.to_thread(report, decision, None, e)
            raise
        await asyncio.to_thread(report, decision, result)
        return result

    return StructuredTool.from_function(func=run, coroutine=arun, name=tool.name, description=tool.description,
                                        args_schema=tool.args_schema, handle_tool_error=True,
                                        return_direct=tool.return_direct)
