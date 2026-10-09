import base64
import copy
import json
import logging
import os
from dataclasses import dataclass, field
from typing import Any, Dict, Mapping, Optional

from nexusphere_ledger import LedgerClient, hash_of

ACTION = "tools/call"
DENIED = -32003
META_KEY = "nexusphere.dev/decisionId"
VERSION = "1.0"
logger = logging.getLogger("nexusphere_agentcore")


@dataclass
class Settings:
    ledger_url: str
    api_key: str
    mode: str = "record"
    agent_id: Optional[str] = None
    agent_claim: str = "client_id"
    principal_claim: str = "sub"
    default_principal: Optional[str] = None
    attributes: Dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        mode = env.get("NEXUSPHERE_MODE", "record").strip().lower()
        if mode not in ("record", "decide"):
            raise ValueError("NEXUSPHERE_MODE must be record or decide")
        return cls(ledger_url=env["NEXUSPHERE_LEDGER_URL"], api_key=env["NEXUSPHERE_LEDGER_API_KEY"], mode=mode,
                   agent_id=env.get("NEXUSPHERE_AGENT_ID") or None,
                   agent_claim=env.get("NEXUSPHERE_AGENT_CLAIM", "client_id"),
                   principal_claim=env.get("NEXUSPHERE_PRINCIPAL_CLAIM", "sub"),
                   default_principal=env.get("NEXUSPHERE_DEFAULT_PRINCIPAL") or None)


class Interceptor:

    def __init__(self, settings: Settings, client: Optional[LedgerClient] = None):
        self.settings = settings
        self.client = client or LedgerClient(settings.ledger_url, settings.api_key)

    def handle(self, event: Mapping[str, Any]) -> dict:
        mcp = event.get("mcp") or {}
        request = mcp.get("gatewayRequest") or {}
        body = request.get("body") if isinstance(request.get("body"), dict) else {}
        response = mcp.get("gatewayResponse")
        if response is not None:
            if body.get("method") == ACTION:
                self._after(request, body, response)
            return {"interceptorOutputVersion": VERSION, "mcp": {"transformedGatewayResponse": {
                "statusCode": response.get("statusCode", 200), "body": response.get("body", {})}}}
        if body.get("method") == ACTION and self.settings.mode == "decide":
            return self._decide(request, body)
        return {"interceptorOutputVersion": VERSION, "mcp": {"transformedGatewayRequest": {"body": body}}}

    def _decide(self, request: Mapping[str, Any], body: dict) -> dict:
        params = body.get("params") if isinstance(body.get("params"), dict) else {}
        agent, principal = self._identity(request)
        if not agent or not principal:
            return self._deny(body, "no agent or principal in the request", None, "UNKNOWN_IDENTITY")
        try:
            decision = self.client.decide(principal, ACTION, str(params.get("name", ""))[:300],
                                          hash_of(params.get("arguments") or {}), self._session(request),
                                          self._attributes(body), agent)
        except Exception as e:
            logger.exception("no decision for %s", params.get("name"))
            return self._deny(body, "the ledger did not decide: " + type(e).__name__, None, "LEDGER_UNAVAILABLE")
        if decision.get("decision") != "ALLOW":
            return self._deny(body, decision.get("reason") or decision.get("reasonCode") or "denied",
                              decision.get("decisionId"), decision.get("reasonCode"))
        forwarded = copy.deepcopy(body)
        forwarded_params = forwarded.setdefault("params", {})
        meta = forwarded_params.get("_meta") if isinstance(forwarded_params.get("_meta"), dict) else {}
        forwarded_params["_meta"] = {**meta, META_KEY: decision["decisionId"]}
        return {"interceptorOutputVersion": VERSION, "mcp": {"transformedGatewayRequest": {"body": forwarded}}}

    def _after(self, request: Mapping[str, Any], body: dict, response: Mapping[str, Any]) -> None:
        answer = response.get("body") if isinstance(response.get("body"), dict) else {}
        error = answer.get("error") if isinstance(answer.get("error"), dict) else None
        if error is not None and error.get("code") == DENIED:
            return
        params = body.get("params") if isinstance(body.get("params"), dict) else {}
        result = answer.get("result") if isinstance(answer.get("result"), dict) else {}
        failed = error is not None or bool(result.get("isError")) or int(response.get("statusCode") or 200) >= 400
        outcome = "FAILED" if failed else "SUCCEEDED"
        reason = None
        if error is not None:
            reason = ("JSON-RPC %s %s" % (error.get("code"), error.get("message") or "")).strip()[:500]
        elif failed:
            reason = "tool returned an error"
        output = hash_of(result) if result else None
        meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
        try:
            if meta.get(META_KEY):
                self.client.report_outcome(meta[META_KEY], outcome, output, reason, self._attributes(body))
                return
            agent, principal = self._identity(request)
            if not agent or not principal:
                logger.warning("tool call %s not recorded: no agent or principal", params.get("name"))
                return
            self.client.record(agent, principal, ACTION, outcome, target=str(params.get("name", ""))[:300],
                               reason=reason, input_hash=hash_of(params.get("arguments") or {}), output_hash=output,
                               correlation_id=self._session(request), attributes=self._attributes(body))
        except Exception:
            logger.exception("tool call %s not recorded", params.get("name"))

    def _deny(self, body: dict, reason: str, decision_id: Optional[str], reason_code: Optional[str]) -> dict:
        data = {"reasonCode": reason_code}
        if decision_id:
            data["decisionId"] = decision_id
        return {"interceptorOutputVersion": VERSION, "mcp": {
            "transformedGatewayRequest": {"body": body},
            "transformedGatewayResponse": {"statusCode": 200, "body": {
                "jsonrpc": "2.0", "id": body.get("id"),
                "error": {"code": DENIED, "message": "Denied by Nexusphere Ledger: " + reason, "data": data}}}}}

    def _identity(self, request: Mapping[str, Any]):
        claims = _claims(_header(request, "authorization"))
        agent = self.settings.agent_id or _text(claims.get(self.settings.agent_claim))
        principal = _text(claims.get(self.settings.principal_claim)) or self.settings.default_principal
        return agent, principal

    def _session(self, request: Mapping[str, Any]) -> Optional[str]:
        session = _header(request, "mcp-session-id")
        return session[:128] if session else None

    def _attributes(self, body: Mapping[str, Any]) -> Dict[str, str]:
        attributes = {"gateway": "agentcore", **self.settings.attributes}
        if body.get("id") is not None:
            attributes["jsonrpc.id"] = str(body.get("id"))[:1024]
        return attributes


def _header(request: Mapping[str, Any], name: str) -> Optional[str]:
    for key, value in (request.get("headers") or {}).items():
        if key.lower() == name:
            return value
    return None


def _claims(authorization: Optional[str]) -> dict:
    if not authorization or not authorization.lower().startswith("bearer "):
        return {}
    parts = authorization[7:].strip().split(".")
    if len(parts) != 3:
        return {}
    try:
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    except ValueError:
        return {}
    return payload if isinstance(payload, dict) else {}


def _text(value) -> Optional[str]:
    return str(value)[:200] if value not in (None, "") else None


_interceptor: Optional[Interceptor] = None


def lambda_handler(event, context):
    global _interceptor
    if _interceptor is None:
        _interceptor = Interceptor(Settings.from_env())
    return _interceptor.handle(event)
