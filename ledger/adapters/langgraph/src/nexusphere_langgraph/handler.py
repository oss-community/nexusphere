import threading
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from uuid import UUID

from langchain_core.callbacks import BaseCallbackHandler
from nexusphere_ledger import LedgerClient, hash_of

from ._common import ACTION, PRINCIPAL_KEY, correlation_of, failed, logger, output_hash, principal_of


class NexusphereCallbackHandler(BaseCallbackHandler):

    def __init__(self, client: LedgerClient, agent_id: str, principal_id: Optional[str] = None,
                 principal_key: str = PRINCIPAL_KEY, attributes: Optional[Dict[str, str]] = None,
                 strict: bool = False):
        self.client = client
        self.agent_id = agent_id
        self.principal_id = principal_id
        self.principal_key = principal_key
        self.attributes = dict(attributes or {})
        self.strict = strict
        self.raise_error = strict
        self.recorded = []
        self._calls: Dict[UUID, dict] = {}
        self._lock = threading.Lock()

    def on_tool_start(self, serialized: Dict[str, Any], input_str: str, *, run_id: UUID,
                      parent_run_id: Optional[UUID] = None, metadata: Optional[Dict[str, Any]] = None,
                      inputs: Optional[Dict[str, Any]] = None, **kwargs: Any) -> None:
        call = {
            "occurred_at": datetime.now(timezone.utc),
            "target": (serialized or {}).get("name") or kwargs.get("name") or "tool",
            "input_hash": hash_of(inputs if inputs is not None else input_str),
            "principal": principal_of(metadata, self.principal_key, self.principal_id),
            "correlation": correlation_of(metadata, parent_run_id or run_id),
            "tool_call_id": kwargs.get("tool_call_id"),
        }
        with self._lock:
            self._calls[run_id] = call

    def on_tool_end(self, output: Any, *, run_id: UUID, **kwargs: Any) -> None:
        call = self._take(run_id)
        if call is not None:
            reason = "tool returned an error" if failed(output) else None
            self._record(call, "FAILED" if failed(output) else "SUCCEEDED", output_hash(output), reason)

    def on_tool_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        call = self._take(run_id)
        if call is not None:
            self._record(call, "FAILED", None, type(error).__name__)

    def _take(self, run_id: UUID) -> Optional[dict]:
        with self._lock:
            return self._calls.pop(run_id, None)

    def _record(self, call: dict, outcome: str, out_hash: Optional[str], reason: Optional[str]) -> None:
        if not call["principal"]:
            logger.warning("tool call %s not recorded: no principal in metadata key %s",
                           call["target"], self.principal_key)
            return
        attributes = {"framework": "langgraph", **self.attributes}
        if call["tool_call_id"]:
            attributes["tool_call_id"] = str(call["tool_call_id"])[:1024]
        try:
            self.recorded.append(self.client.record(
                self.agent_id, call["principal"], ACTION, outcome, occurred_at=call["occurred_at"],
                target=str(call["target"])[:300], reason=reason, input_hash=call["input_hash"],
                output_hash=out_hash, correlation_id=call["correlation"], attributes=attributes))
        except Exception:
            if self.strict:
                raise
            logger.exception("tool call %s not recorded", call["target"])
