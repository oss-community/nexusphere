package nexusphereexporter

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"

	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/ptrace"
)

const maxAttributes = 32

var (
	attributeName = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	sha256Hex     = regexp.MustCompile(`^[0-9a-f]{64}$`)
	copied        = []string{"gen_ai.provider.name", "gen_ai.request.model", "gen_ai.tool.call.id", "gen_ai.tool.type",
		"gen_ai.agent.name", "mcp.session.id", "service.name"}
)

type evidence struct {
	OccurredAt    string            `json:"occurredAt"`
	AgentID       string            `json:"agentId"`
	PrincipalID   string            `json:"principalId"`
	Action        string            `json:"action"`
	Target        string            `json:"target,omitempty"`
	Decision      string            `json:"decision,omitempty"`
	Reason        string            `json:"reason,omitempty"`
	DelegationID  string            `json:"delegationId,omitempty"`
	InputHash     string            `json:"inputHash,omitempty"`
	OutputHash    string            `json:"outputHash,omitempty"`
	Outcome       string            `json:"outcome"`
	CorrelationID string            `json:"correlationId,omitempty"`
	Attributes    map[string]string `json:"attributes"`
}

type skip string

const (
	notSelected  skip = ""
	noAgent      skip = "no agent"
	noPrincipal  skip = "no principal"
	selectedSpan skip = "selected"
)

type lookup struct {
	span     pcommon.Map
	resource pcommon.Map
}

func (l lookup) get(key string) string {
	if value, ok := l.span.Get(key); ok {
		return value.AsString()
	}
	if value, ok := l.resource.Get(key); ok {
		return value.AsString()
	}
	return ""
}

func (l lookup) first(keys []string, fallback string) string {
	for _, key := range keys {
		if value := strings.TrimSpace(l.get(key)); value != "" {
			return value
		}
	}
	return fallback
}

func toEvidence(cfg *Config, resource pcommon.Resource, span ptrace.Span) (evidence, skip) {
	attrs := lookup{span: span.Attributes(), resource: resource.Attributes()}
	operation := attrs.get("gen_ai.operation.name")
	method := attrs.get("mcp.method.name")
	if !slices.Contains(cfg.Operations, operation) && !slices.Contains(cfg.Operations, method) {
		return evidence{}, notSelected
	}
	agent := attrs.first(cfg.AgentAttributes, cfg.DefaultAgent)
	if agent == "" {
		return evidence{}, noAgent
	}
	principal := attrs.first(cfg.PrincipalAttributes, cfg.DefaultPrincipal)
	if principal == "" {
		return evidence{}, noPrincipal
	}
	e := evidence{
		OccurredAt:  span.StartTimestamp().AsTime().UTC().Format(time.RFC3339Nano),
		AgentID:     text(agent, 200),
		PrincipalID: text(principal, 200),
		Action:      text(action(operation, method), 120),
		Target:      text(target(attrs, operation, span.Name()), 300),
		Attributes:  map[string]string{},
	}
	switch decision := strings.ToUpper(attrs.get("nexusphere.decision")); decision {
	case "ALLOW", "DENY":
		e.Decision = decision
	}
	switch {
	case e.Decision == "DENY":
		e.Outcome = "DENIED"
	case span.Status().Code() == ptrace.StatusCodeError:
		e.Outcome = "FAILED"
		e.Reason = text(firstNonEmpty(attrs.get("error.type"), span.Status().Message()), 500)
	default:
		e.Outcome = "SUCCEEDED"
	}
	if reason := attrs.get("nexusphere.reason"); reason != "" {
		e.Reason = text(reason, 500)
	}
	e.DelegationID = text(attrs.get("nexusphere.delegation.id"), 200)
	e.CorrelationID = text(firstNonEmpty(attrs.get("gen_ai.conversation.id"), span.TraceID().String()), 128)
	e.InputHash = contentHash(cfg, attrs, "nexusphere.input.hash", "gen_ai.tool.call.arguments")
	e.OutputHash = contentHash(cfg, attrs, "nexusphere.output.hash", "gen_ai.tool.call.result")
	e.Attributes["otel.trace_id"] = span.TraceID().String()
	e.Attributes["otel.span_id"] = span.SpanID().String()
	e.Attributes["otel.span_name"] = text(span.Name(), 1024)
	duration := span.EndTimestamp().AsTime().Sub(span.StartTimestamp().AsTime())
	e.Attributes["otel.duration_ms"] = strconv.FormatInt(duration.Milliseconds(), 10)
	for _, key := range append(slices.Clone(copied), cfg.Attributes...) {
		if len(e.Attributes) >= maxAttributes {
			break
		}
		if value := attrs.get(key); value != "" && attributeName.MatchString(key) {
			e.Attributes[key] = truncate(value, 1024)
		}
	}
	return e, selectedSpan
}

func action(operation, method string) string {
	switch {
	case method != "":
		return method
	case operation == "execute_tool":
		return "tools/call"
	case operation == "invoke_agent":
		return "agents/invoke"
	default:
		return "gen_ai/" + operation
	}
}

func target(attrs lookup, operation, spanName string) string {
	if tool := attrs.get("gen_ai.tool.name"); tool != "" {
		return tool
	}
	if operation == "invoke_agent" {
		if name := attrs.get("gen_ai.agent.name"); name != "" {
			return name
		}
	}
	return spanName
}

func contentHash(cfg *Config, attrs lookup, given, content string) string {
	if value := strings.ToLower(attrs.get(given)); sha256Hex.MatchString(value) {
		return value
	}
	if !cfg.HashContent {
		return ""
	}
	if value, ok := attrs.span.Get(content); ok {
		sum := sha256.Sum256([]byte(value.AsString()))
		return hex.EncodeToString(sum[:])
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func text(value string, limit int) string {
	cleaned := strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, value)
	return truncate(strings.TrimSpace(cleaned), limit)
}

func truncate(value string, limit int) string {
	units := 0
	for index, r := range value {
		size := 1
		if r > 0xffff {
			size = 2
		}
		if units+size > limit {
			return value[:index]
		}
		units += size
	}
	return value
}
