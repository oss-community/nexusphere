import json
import logging
from typing import Any, Mapping, Optional

from nexusphere_ledger import hash_of

ACTION = "tools/call"
PRINCIPAL_KEY = "nexusphere_principal"
logger = logging.getLogger("nexusphere_langgraph")


def principal_of(metadata: Optional[Mapping[str, Any]], key: str, default: Optional[str]) -> Optional[str]:
    value = (metadata or {}).get(key)
    if value is None:
        value = ((metadata or {}).get("configurable") or {}).get(key)
    return str(value) if value else default


def correlation_of(metadata: Optional[Mapping[str, Any]], fallback) -> Optional[str]:
    metadata = metadata or {}
    value = metadata.get("thread_id") or (metadata.get("configurable") or {}).get("thread_id") or fallback
    return None if value is None else str(value)[:128]


def content_of(output) -> Any:
    content = getattr(output, "content", output)
    if isinstance(content, (str, dict, list)):
        return content
    try:
        return json.dumps(content, default=str, sort_keys=True)
    except (TypeError, ValueError):
        return str(content)


def failed(output) -> bool:
    return getattr(output, "status", None) == "error"


def output_hash(output) -> str:
    return hash_of(content_of(output))
