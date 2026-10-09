package nexusphereexporter

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/ptrace"
)

var start = time.Date(2026, 10, 1, 8, 0, 0, 123456789, time.UTC)

func toolSpan(traces ptrace.Traces, attributes map[string]any) ptrace.Span {
	resource := traces.ResourceSpans().AppendEmpty()
	resource.Resource().Attributes().PutStr("service.name", "invoice-service")
	span := resource.ScopeSpans().AppendEmpty().Spans().AppendEmpty()
	span.SetName("execute_tool read_invoice")
	span.SetTraceID(pcommon.TraceID([16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}))
	span.SetSpanID(pcommon.SpanID([8]byte{1, 2, 3, 4, 5, 6, 7, 8}))
	span.SetStartTimestamp(pcommon.NewTimestampFromTime(start))
	span.SetEndTimestamp(pcommon.NewTimestampFromTime(start.Add(250 * time.Millisecond)))
	if err := span.Attributes().FromRaw(attributes); err != nil {
		panic(err)
	}
	return span
}

func tool(extra map[string]any) map[string]any {
	attributes := map[string]any{
		"gen_ai.operation.name": "execute_tool",
		"gen_ai.tool.name":      "read_invoice",
		"gen_ai.agent.id":       "invoice-agent",
		"user.id":               "alice",
	}
	for key, value := range extra {
		attributes[key] = value
	}
	return attributes
}

func mapOne(t *testing.T, cfg *Config, attributes map[string]any, change func(ptrace.Span)) (evidence, skip) {
	t.Helper()
	traces := ptrace.NewTraces()
	span := toolSpan(traces, attributes)
	if change != nil {
		change(span)
	}
	return toEvidence(cfg, traces.ResourceSpans().At(0).Resource(), span)
}

func defaults() *Config {
	return createDefaultConfig().(*Config)
}

func TestToolCallBecomesEvidence(t *testing.T) {
	e, result := mapOne(t, defaults(), tool(map[string]any{
		"gen_ai.tool.call.arguments": `{"invoice":7}`,
		"gen_ai.tool.call.result":    "invoice text",
		"gen_ai.conversation.id":     "conversation-1",
		"gen_ai.request.model":       "claude-sonnet",
	}), nil)
	if result != selectedSpan {
		t.Fatalf("span not selected: %q", result)
	}
	arguments := sha256.Sum256([]byte(`{"invoice":7}`))
	output := sha256.Sum256([]byte("invoice text"))
	checks := map[string][2]string{
		"occurredAt":    {e.OccurredAt, "2026-10-01T08:00:00.123456789Z"},
		"agentId":       {e.AgentID, "invoice-agent"},
		"principalId":   {e.PrincipalID, "alice"},
		"action":        {e.Action, "tools/call"},
		"target":        {e.Target, "read_invoice"},
		"outcome":       {e.Outcome, "SUCCEEDED"},
		"decision":      {e.Decision, ""},
		"inputHash":     {e.InputHash, hex.EncodeToString(arguments[:])},
		"outputHash":    {e.OutputHash, hex.EncodeToString(output[:])},
		"correlationId": {e.CorrelationID, "conversation-1"},
		"trace":         {e.Attributes["otel.trace_id"], "0102030405060708090a0b0c0d0e0f10"},
		"span":          {e.Attributes["otel.span_id"], "0102030405060708"},
		"duration":      {e.Attributes["otel.duration_ms"], "250"},
		"model":         {e.Attributes["gen_ai.request.model"], "claude-sonnet"},
		"service":       {e.Attributes["service.name"], "invoice-service"},
	}
	for name, pair := range checks {
		if pair[0] != pair[1] {
			t.Errorf("%s = %q, want %q", name, pair[0], pair[1])
		}
	}
	for key, value := range e.Attributes {
		if strings.Contains(value, "invoice text") || strings.Contains(key, "arguments") {
			t.Errorf("tool content leaked into attribute %s", key)
		}
	}
}

func TestSpanSelection(t *testing.T) {
	cfg := defaults()
	if _, result := mapOne(t, cfg, map[string]any{"gen_ai.operation.name": "chat", "gen_ai.agent.id": "a",
		"user.id": "b"}, nil); result != notSelected {
		t.Errorf("chat span selected")
	}
	e, result := mapOne(t, cfg, map[string]any{"mcp.method.name": "tools/call", "gen_ai.tool.name": "send_email",
		"gen_ai.agent.id": "a", "user.id": "b"}, nil)
	if result != selectedSpan || e.Action != "tools/call" || e.Target != "send_email" {
		t.Errorf("MCP span mapped to %+v (%q)", e, result)
	}
	e, _ = mapOne(t, cfg, map[string]any{"gen_ai.operation.name": "invoke_agent", "gen_ai.agent.name": "supplier",
		"gen_ai.agent.id": "a", "user.id": "b"}, nil)
	if e.Action != "agents/invoke" || e.Target != "supplier" {
		t.Errorf("agent span mapped to %s on %s", e.Action, e.Target)
	}
	cfg.Operations = []string{"chat"}
	if e, result := mapOne(t, cfg, map[string]any{"gen_ai.operation.name": "chat", "gen_ai.agent.id": "a",
		"user.id": "b"}, nil); result != selectedSpan || e.Action != "gen_ai/chat" {
		t.Errorf("configured operation not selected: %q", result)
	}
}

func TestAgentAndPrincipal(t *testing.T) {
	cfg := defaults()
	attributes := tool(nil)
	delete(attributes, "user.id")
	if _, result := mapOne(t, cfg, attributes, nil); result != noPrincipal {
		t.Errorf("span without principal gave %q", result)
	}
	cfg.DefaultPrincipal = "acme"
	if e, _ := mapOne(t, cfg, attributes, nil); e.PrincipalID != "acme" {
		t.Errorf("default principal not used: %q", e.PrincipalID)
	}
	delete(attributes, "gen_ai.agent.id")
	if _, result := mapOne(t, cfg, attributes, nil); result != noAgent {
		t.Errorf("span without agent gave %q", result)
	}
	attributes["nexusphere.principal.id"] = "bob"
	attributes["user.id"] = "alice"
	cfg.DefaultAgent = "gateway"
	if e, _ := mapOne(t, cfg, attributes, nil); e.AgentID != "gateway" || e.PrincipalID != "bob" {
		t.Errorf("agent %q and principal %q", e.AgentID, e.PrincipalID)
	}
}

func TestOutcomes(t *testing.T) {
	cfg := defaults()
	e, _ := mapOne(t, cfg, tool(map[string]any{"error.type": "timeout"}), func(span ptrace.Span) {
		span.Status().SetCode(ptrace.StatusCodeError)
		span.Status().SetMessage("deadline exceeded")
	})
	if e.Outcome != "FAILED" || e.Reason != "timeout" {
		t.Errorf("failed span gave %s with %q", e.Outcome, e.Reason)
	}
	e, _ = mapOne(t, cfg, tool(map[string]any{"nexusphere.decision": "deny", "nexusphere.reason": "NOT_COVERED",
		"nexusphere.delegation.id": "grant-1"}), nil)
	if e.Decision != "DENY" || e.Outcome != "DENIED" || e.Reason != "NOT_COVERED" || e.DelegationID != "grant-1" {
		t.Errorf("denied span gave %+v", e)
	}
	e, _ = mapOne(t, cfg, tool(map[string]any{"mcp.error.code": int64(-32602), "mcp.error.message": "Unknown tool"}), nil)
	if e.Outcome != "FAILED" || e.Reason != "JSON-RPC -32602 Unknown tool" {
		t.Errorf("JSON-RPC error gave %s with %q", e.Outcome, e.Reason)
	}
	e, _ = mapOne(t, cfg, tool(map[string]any{"nexusphere.decision": "ALLOW"}), nil)
	if e.Decision != "ALLOW" || e.Outcome != "SUCCEEDED" {
		t.Errorf("allowed span gave %s %s", e.Decision, e.Outcome)
	}
}

func TestHashesAndLimits(t *testing.T) {
	cfg := defaults()
	given := strings.Repeat("ab", 32)
	e, _ := mapOne(t, cfg, tool(map[string]any{"nexusphere.input.hash": strings.ToUpper(given),
		"gen_ai.tool.call.arguments": "ignored"}), nil)
	if e.InputHash != given {
		t.Errorf("given hash not used: %s", e.InputHash)
	}
	cfg.HashContent = false
	e, _ = mapOne(t, cfg, tool(map[string]any{"gen_ai.tool.call.arguments": "secret"}), nil)
	if e.InputHash != "" {
		t.Errorf("content hashed although hash_content is off")
	}
	cfg.Attributes = []string{"bad key!", "custom.one"}
	long := strings.Repeat("é", 400) + "\n" + strings.Repeat("x", 2000)
	e, _ = mapOne(t, cfg, tool(map[string]any{"gen_ai.tool.name": long, "custom.one": long, "bad key!": "x"}), nil)
	if len([]rune(e.Target)) != 300 || strings.ContainsRune(e.Target, '\n') {
		t.Errorf("target not cleaned: %d runes", len([]rune(e.Target)))
	}
	if len([]rune(e.Attributes["custom.one"])) != 1024 {
		t.Errorf("attribute not truncated")
	}
	if _, ok := e.Attributes["bad key!"]; ok {
		t.Errorf("invalid attribute name copied")
	}
	if truncate("a\U0001F600b", 2) != "a" || truncate("abc", 3) != "abc" {
		t.Errorf("truncate counts UTF-16 units wrongly")
	}
}
