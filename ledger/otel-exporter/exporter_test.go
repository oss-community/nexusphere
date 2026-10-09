package nexusphereexporter

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"go.opentelemetry.io/collector/component/componenttest"
	"go.opentelemetry.io/collector/config/configopaque"
	"go.opentelemetry.io/collector/confmap"
	"go.opentelemetry.io/collector/confmap/confmaptest"
	"go.opentelemetry.io/collector/consumer/consumererror"
	"go.opentelemetry.io/collector/exporter/exportertest"
	"go.opentelemetry.io/collector/pdata/ptrace"
)

type fakeLedger struct {
	mu      sync.Mutex
	batches [][]map[string]any
	keys    []string
	status  int
}

func (f *fakeLedger) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.URL.Path != "/api/v1/evidence/batch" || r.Method != http.MethodPost {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	var body struct {
		Items []map[string]any `json:"items"`
	}
	data, _ := io.ReadAll(r.Body)
	_ = json.Unmarshal(data, &body)
	f.batches = append(f.batches, body.Items)
	f.keys = append(f.keys, r.Header.Get("Authorization"))
	if f.status != 0 {
		w.WriteHeader(f.status)
		_, _ = w.Write([]byte(`{"code":"INVALID_REQUEST","message":"The evidence has invalid fields."}`))
		return
	}
	w.WriteHeader(http.StatusCreated)
	_, _ = w.Write([]byte(`{"items":[]}`))
}

func newTestExporter(t *testing.T, endpoint string, change func(*Config)) *ledgerExporter {
	t.Helper()
	cfg := defaults()
	cfg.ClientConfig.Endpoint = endpoint
	cfg.APIKey = "agent-key"
	if change != nil {
		change(cfg)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	e := newLedgerExporter(cfg, exportertest.NewNopSettings(componentType))
	if err := e.start(context.Background(), componenttest.NewNopHost()); err != nil {
		t.Fatal(err)
	}
	return e
}

func traces(count int) ptrace.Traces {
	out := ptrace.NewTraces()
	for i := 0; i < count; i++ {
		toolSpan(out, tool(map[string]any{"gen_ai.tool.call.id": fmt.Sprintf("call-%d", i)}))
	}
	toolSpan(out, map[string]any{"gen_ai.operation.name": "chat"})
	return out
}

func TestPushSendsBatches(t *testing.T) {
	ledger := &fakeLedger{}
	server := httptest.NewServer(ledger)
	defer server.Close()
	e := newTestExporter(t, server.URL+"/", func(c *Config) { c.BatchSize = 2 })
	if err := e.pushTraces(context.Background(), traces(5)); err != nil {
		t.Fatal(err)
	}
	if len(ledger.batches) != 3 || len(ledger.batches[0]) != 2 || len(ledger.batches[2]) != 1 {
		t.Fatalf("batches %v", ledger.batches)
	}
	if ledger.keys[0] != "Bearer agent-key" {
		t.Errorf("authorization %q", ledger.keys[0])
	}
	first := ledger.batches[0][0]
	if first["agentId"] != "invoice-agent" || first["action"] != "tools/call" || first["outcome"] != "SUCCEEDED" {
		t.Errorf("first item %v", first)
	}
	if _, ok := first["decision"]; ok {
		t.Errorf("empty decision sent")
	}
}

func TestRefusalIsPermanent(t *testing.T) {
	ledger := &fakeLedger{status: http.StatusBadRequest}
	server := httptest.NewServer(ledger)
	defer server.Close()
	err := newTestExporter(t, server.URL, nil).pushTraces(context.Background(), traces(1))
	if !consumererror.IsPermanent(err) {
		t.Errorf("400 should be permanent, got %v", err)
	}
	ledger.status = http.StatusInternalServerError
	err = newTestExporter(t, server.URL, nil).pushTraces(context.Background(), traces(1))
	if err == nil || consumererror.IsPermanent(err) {
		t.Errorf("500 should be retried, got %v", err)
	}
	ledger.status = http.StatusTooManyRequests
	err = newTestExporter(t, server.URL, nil).pushTraces(context.Background(), traces(1))
	if err == nil || consumererror.IsPermanent(err) {
		t.Errorf("429 should be retried, got %v", err)
	}
}

func TestNothingSelectedSendsNothing(t *testing.T) {
	ledger := &fakeLedger{}
	server := httptest.NewServer(ledger)
	defer server.Close()
	if err := newTestExporter(t, server.URL, nil).pushTraces(context.Background(), traces(0)); err != nil {
		t.Fatal(err)
	}
	if len(ledger.batches) != 0 {
		t.Errorf("sent %d batches", len(ledger.batches))
	}
}

func TestConfigFromYaml(t *testing.T) {
	conf, err := confmaptest.LoadConf(filepath.Join("testdata", "config.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := createDefaultConfig().(*Config)
	if err := conf.Unmarshal(cfg); err != nil {
		t.Fatal(err)
	}
	if err := confmap.Validate(cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.ClientConfig.Endpoint != "http://localhost:8090" || string(cfg.APIKey) != "secret" ||
		cfg.DefaultPrincipal != "acme" || cfg.BatchSize != 100 || cfg.HashContent ||
		len(cfg.Operations) != 1 || cfg.ClientConfig.Timeout != 5*time.Second {
		t.Errorf("config %+v", cfg)
	}
	missing := createDefaultConfig().(*Config)
	if err := missing.Validate(); err == nil {
		t.Errorf("config without endpoint accepted")
	}
	missing.ClientConfig.Endpoint = "http://localhost:8090"
	if err := missing.Validate(); err == nil {
		t.Errorf("config without api_key accepted")
	}
	missing.APIKey = "key"
	missing.BatchSize = 501
	if err := missing.Validate(); err == nil {
		t.Errorf("batch_size 501 accepted")
	}
}

func TestFactoryBuildsTracesExporter(t *testing.T) {
	factory := NewFactory()
	cfg := factory.CreateDefaultConfig().(*Config)
	cfg.ClientConfig.Endpoint = "http://localhost:8090"
	cfg.APIKey = "key"
	exp, err := factory.CreateTraces(context.Background(), exportertest.NewNopSettings(componentType), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := exp.Start(context.Background(), componenttest.NewNopHost()); err != nil {
		t.Fatal(err)
	}
	if err := exp.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	if factory.Type().String() != "nexusphere" {
		t.Errorf("type %s", factory.Type())
	}
}

func TestLiveLedger(t *testing.T) {
	url := os.Getenv("NEXUSPHERE_LEDGER_URL")
	if url == "" {
		t.Skip("set NEXUSPHERE_LEDGER_URL to run against a running ledger")
	}
	key := os.Getenv("NEXUSPHERE_LEDGER_API_KEY")
	if key == "" {
		key = "nexusphere-ledger-development-key-change-me"
	}
	e := newTestExporter(t, url, func(c *Config) { c.APIKey = "wrong" })
	if err := e.pushTraces(context.Background(), traces(1)); !consumererror.IsPermanent(err) {
		t.Fatalf("a wrong key should be refused for good, got %v", err)
	}
	e = newTestExporter(t, url, func(c *Config) { c.APIKey = configopaque.String(key) })
	if err := e.pushTraces(context.Background(), traces(3)); err != nil {
		t.Fatal(err)
	}
	request, _ := http.NewRequest(http.MethodGet, url+"/api/v1/evidence?agentId=invoice-agent&limit=500", nil)
	request.Header.Set("Authorization", "Bearer "+key)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var page struct {
		Items []map[string]any `json:"items"`
	}
	if err := json.NewDecoder(response.Body).Decode(&page); err != nil {
		t.Fatal(err)
	}
	found := 0
	for _, item := range page.Items {
		attributes, _ := item["attributes"].(map[string]any)
		if attributes["otel.span_id"] == "0102030405060708" && item["principalId"] == "alice" {
			found++
		}
	}
	if found < 3 {
		t.Errorf("found %d recorded spans", found)
	}
}
