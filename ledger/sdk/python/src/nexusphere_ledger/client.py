import json
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from typing import Iterator, List, Optional

from .canonical import sha256_hex
from .timestamps import format_instant

OUTCOMES = ("SUCCEEDED", "FAILED", "DENIED", "PENDING")


class LedgerError(Exception):

    def __init__(self, status: int, code: Optional[str], message: str, details: Optional[dict] = None):
        super().__init__("%d %s: %s" % (status, code or "ERROR", message))
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}


class Denied(Exception):

    def __init__(self, decision: dict):
        super().__init__("%s: %s" % (decision.get("reasonCode"), decision.get("reason")))
        self.decision = decision


def hash_of(data) -> str:
    if isinstance(data, (dict, list)):
        data = json.dumps(data, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
    return sha256_hex(data)


class Action:

    def __init__(self, client: "LedgerClient", decision: dict):
        self.client = client
        self.decision = decision
        self.output_hash = None
        self.attributes = None
        self.reported = None

    @property
    def decision_id(self) -> str:
        return self.decision["decisionId"]

    def output(self, data) -> None:
        self.output_hash = hash_of(data)


class LedgerClient:

    def __init__(self, base_url: str, api_key: Optional[str] = None, timeout: float = 10.0, opener=None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._opener = opener or urllib.request.build_opener()

    def record(self, agent_id: Optional[str] = None, principal_id: Optional[str] = None,
               action: Optional[str] = None, outcome: Optional[str] = None, **fields) -> dict:
        return self._post("/api/v1/evidence", self._evidence(agent_id=agent_id, principal_id=principal_id,
                                                              action=action, outcome=outcome, **fields))

    def record_batch(self, items: List[dict]) -> List[dict]:
        body = {"items": [self._evidence(**_snake(item)) for item in items]}
        return self._post("/api/v1/evidence/batch", body)["items"]

    def evidence(self, evidence_id: str) -> dict:
        return self._get("/api/v1/evidence/" + _segment(evidence_id))

    def list_evidence(self, agent_id: Optional[str] = None, principal_id: Optional[str] = None, after: int = 0,
                      limit: int = 100) -> dict:
        return self._get("/api/v1/evidence", agentId=agent_id, principalId=principal_id, after=after, limit=limit)

    def iter_evidence(self, agent_id: Optional[str] = None, principal_id: Optional[str] = None, after: int = 0,
                      limit: int = 500) -> Iterator[dict]:
        while True:
            page = self.list_evidence(agent_id, principal_id, after, limit)
            yield from page["items"]
            if page.get("nextAfter") is None:
                return
            after = page["nextAfter"]

    def statement(self, evidence_id: str) -> bytes:
        return self._request("GET", "/api/v1/evidence/%s/statement" % _segment(evidence_id), raw=True)

    def receipt(self, evidence_id: str, tree_size: Optional[int] = None) -> bytes:
        return self._request("GET", "/api/v1/evidence/%s/receipt" % _segment(evidence_id),
                             query={"treeSize": tree_size}, raw=True)

    def head(self) -> dict:
        return self._get("/api/v1/ledger/head")

    def create_checkpoint(self) -> dict:
        return self._post("/api/v1/checkpoints", None)

    def latest_checkpoint(self) -> dict:
        return self._get("/api/v1/checkpoints/latest")

    def checkpoints(self, after: int = 0, limit: int = 100) -> dict:
        return self._get("/api/v1/checkpoints", after=after, limit=limit)

    def inclusion_proof(self, sequence: int, tree_size: Optional[int] = None) -> dict:
        return self._get("/api/v1/log/proofs/inclusion", sequence=sequence, treeSize=tree_size)

    def consistency_proof(self, first_size: int, second_size: Optional[int] = None) -> dict:
        return self._get("/api/v1/log/proofs/consistency", firstSize=first_size, secondSize=second_size)

    def keys(self) -> List[dict]:
        return self._get("/api/v1/keys")

    def active_public_key(self) -> str:
        return next(key["publicKey"] for key in self.keys() if key.get("status") == "ACTIVE")

    def export_package(self, agent_id: Optional[str] = None, principal_id: Optional[str] = None,
                       from_sequence: Optional[int] = None, to_sequence: Optional[int] = None) -> dict:
        return self._post("/api/v1/packages", _compact({"agentId": agent_id, "principalId": principal_id,
                                                        "fromSequence": from_sequence, "toSequence": to_sequence}))

    def decide(self, principal_id: str, action: str, target: Optional[str] = None, input_hash: Optional[str] = None,
               correlation_id: Optional[str] = None, attributes: Optional[dict] = None,
               agent_id: Optional[str] = None) -> dict:
        return self._post("/api/v1/decisions", _compact({
            "agentId": agent_id, "principalId": principal_id, "action": action, "target": target,
            "inputHash": input_hash, "correlationId": correlation_id, "attributes": attributes}))

    def report_outcome(self, decision_id: str, outcome: str, output_hash: Optional[str] = None,
                       reason: Optional[str] = None, attributes: Optional[dict] = None) -> dict:
        return self._post("/api/v1/decisions/%s/outcome" % _segment(decision_id), _compact({
            "outcome": outcome, "outputHash": output_hash, "reason": reason, "attributes": attributes}))

    def decision(self, decision_id: str) -> dict:
        return self._get("/api/v1/decisions/" + _segment(decision_id))

    @contextmanager
    def act(self, principal_id: str, action: str, target: Optional[str] = None, input=None,
            correlation_id: Optional[str] = None, attributes: Optional[dict] = None,
            agent_id: Optional[str] = None):
        decision = self.decide(principal_id, action, target, None if input is None else hash_of(input),
                               correlation_id, attributes, agent_id)
        if decision.get("decision") != "ALLOW":
            raise Denied(decision)
        handle = Action(self, decision)
        try:
            yield handle
        except BaseException as e:
            handle.reported = self.report_outcome(handle.decision_id, "FAILED", handle.output_hash,
                                                  type(e).__name__, handle.attributes)
            raise
        handle.reported = self.report_outcome(handle.decision_id, "SUCCEEDED", handle.output_hash, None,
                                              handle.attributes)

    def register_agent(self, agent_id: str, name: Optional[str] = None, owner_id: Optional[str] = None) -> dict:
        return self._post("/api/v1/agents", _compact({"agentId": agent_id, "name": name, "ownerId": owner_id}))

    def set_signing_key(self, agent_id: str, public_key) -> dict:
        encoded = public_key if isinstance(public_key, str) else public_key.encoded
        return self._request("PUT", "/api/v1/agents/%s/signing-key" % _segment(agent_id),
                             body={"publicKey": encoded})

    def create_grant(self, principal_id: str, agent_id: str, actions: List[str], targets: List[str], expires_at,
                     not_before=None, max_uses: Optional[int] = None) -> dict:
        return self._post("/api/v1/grants", _compact({
            "principalId": principal_id, "agentId": agent_id, "actions": list(actions), "targets": list(targets),
            "expiresAt": format_instant(expires_at),
            "notBefore": None if not_before is None else format_instant(not_before), "maxUses": max_uses}))

    def grant(self, grant_id: str) -> dict:
        return self._get("/api/v1/grants/" + _segment(grant_id))

    def revoke_grant(self, grant_id: str, reason: Optional[str] = None) -> dict:
        return self._post("/api/v1/grants/%s/revoke" % _segment(grant_id), _compact({"reason": reason}))

    def issue_mandate(self, grant_id: str, audience: Optional[str] = None, expires_at=None) -> dict:
        return self._post("/api/v1/mandates", _compact({
            "grantId": grant_id, "audience": audience,
            "expiresAt": None if expires_at is None else format_instant(expires_at)}))

    def revoke_mandate(self, mandate_id: str, reason: Optional[str] = None) -> dict:
        return self._post("/api/v1/mandates/%s/revoke" % _segment(mandate_id), _compact({"reason": reason}))

    def jwks(self) -> dict:
        return self._get("/public/v1/keys")

    def log_checkpoint(self) -> str:
        return self._request("GET", "/public/v1/log/checkpoint", raw=True).decode("utf-8")

    def log_key(self) -> str:
        return self._request("GET", "/public/v1/log/key", raw=True).decode("utf-8").strip()

    def witness_key(self) -> str:
        return self._request("GET", "/public/v1/witness/key", raw=True).decode("utf-8").strip()

    def erase_principal(self, principal_id: str, reason: Optional[str] = None) -> dict:
        return self._post("/api/v1/principals/" + _segment(principal_id) + "/erasure", _compact({"reason": reason}))

    def place_legal_hold(self, principal_id: str, reason: str) -> dict:
        return self._post("/api/v1/legal-holds", {"principalId": principal_id, "reason": reason})

    def release_legal_hold(self, hold_id: str, reason: str) -> dict:
        return self._post("/api/v1/legal-holds/" + _segment(hold_id) + "/release", {"reason": reason})

    def legal_holds(self, active: bool = False) -> dict:
        return self._get("/api/v1/legal-holds", active="true" if active else None)

    def sweep_retention(self) -> dict:
        return self._post("/api/v1/retention/sweep", {})

    def compliance(self) -> dict:
        return self._request("GET", "/public/v1/compliance")

    def health(self) -> dict:
        return self._get("/actuator/health")

    def _evidence(self, agent_id=None, principal_id=None, action=None, outcome=None, occurred_at=None,
                  target=None, decision=None, reason=None, delegation_id=None, input_hash=None, output_hash=None,
                  correlation_id=None, attributes=None, input=None, output=None) -> dict:
        if input is not None and input_hash is None:
            input_hash = hash_of(input)
        if output is not None and output_hash is None:
            output_hash = hash_of(output)
        return _compact({
            "occurredAt": None if occurred_at is None else format_instant(occurred_at),
            "agentId": agent_id, "principalId": principal_id, "action": action, "target": target,
            "decision": decision, "reason": reason, "delegationId": delegation_id, "inputHash": input_hash,
            "outputHash": output_hash, "outcome": outcome, "correlationId": correlation_id,
            "attributes": attributes})

    def _get(self, path: str, **query):
        return self._request("GET", path, query=query)

    def _post(self, path: str, body):
        return self._request("POST", path, body=body)

    def _request(self, method: str, path: str, query=None, body=None, raw: bool = False):
        url = self.base_url + path
        params = {key: value for key, value in (query or {}).items() if value is not None}
        if params:
            url += "?" + urllib.parse.urlencode(params)
        headers = {"Accept": "*/*" if raw else "application/json"}
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        data = None
        if body is not None or method == "POST":
            data = json.dumps(body if body is not None else {}).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                content = response.read()
        except urllib.error.HTTPError as e:
            raise _error(e) from None
        if raw:
            return content
        return json.loads(content) if content else None


def _error(e) -> LedgerError:
    try:
        body = json.loads(e.read() or b"{}")
    except ValueError:
        body = {}
    if not isinstance(body, dict):
        body = {}
    return LedgerError(e.code, body.get("code"), body.get("message") or e.reason, body.get("details"))


def _compact(values: dict) -> dict:
    return {key: value for key, value in values.items() if value is not None}


def _segment(value) -> str:
    return urllib.parse.quote(str(value), safe="")


def _snake(item: dict) -> dict:
    out = {}
    for key, value in item.items():
        out["".join("_" + c.lower() if c.isupper() else c for c in key)] = value
    return out
