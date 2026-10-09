package nexusphereexporter

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/consumer/consumererror"
	"go.opentelemetry.io/collector/exporter"
	"go.opentelemetry.io/collector/exporter/exporterhelper"
	"go.opentelemetry.io/collector/pdata/ptrace"
	"go.uber.org/zap"
)

type ledgerExporter struct {
	config   *Config
	settings component.TelemetrySettings
	client   *http.Client
	url      string
}

func newLedgerExporter(config *Config, set exporter.Settings) *ledgerExporter {
	return &ledgerExporter{
		config:   config,
		settings: set.TelemetrySettings,
		url:      strings.TrimRight(config.ClientConfig.Endpoint, "/") + "/api/v1/evidence/batch",
	}
}

func (e *ledgerExporter) start(ctx context.Context, host component.Host) error {
	client, err := e.config.ClientConfig.ToClient(ctx, host.GetExtensions(), e.settings)
	if err != nil {
		return err
	}
	e.client = client
	return nil
}

func (e *ledgerExporter) pushTraces(ctx context.Context, traces ptrace.Traces) error {
	items := e.collect(traces)
	for start := 0; start < len(items); start += e.config.BatchSize {
		end := min(start+e.config.BatchSize, len(items))
		if err := e.send(ctx, items[start:end]); err != nil {
			return err
		}
	}
	return nil
}

func (e *ledgerExporter) collect(traces ptrace.Traces) []evidence {
	var items []evidence
	skipped := map[skip]int{}
	resources := traces.ResourceSpans()
	for i := 0; i < resources.Len(); i++ {
		resource := resources.At(i)
		scopes := resource.ScopeSpans()
		for j := 0; j < scopes.Len(); j++ {
			spans := scopes.At(j).Spans()
			for k := 0; k < spans.Len(); k++ {
				item, result := toEvidence(e.config, resource.Resource(), spans.At(k))
				switch result {
				case selectedSpan:
					items = append(items, item)
				case notSelected:
				default:
					skipped[result]++
				}
			}
		}
	}
	for reason, count := range skipped {
		e.settings.Logger.Debug("Spans not recorded as evidence", zap.String("reason", string(reason)),
			zap.Int("count", count))
	}
	return items
}

func (e *ledgerExporter) send(ctx context.Context, items []evidence) error {
	body, err := json.Marshal(map[string]any{"items": items})
	if err != nil {
		return consumererror.NewPermanent(err)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, e.url, bytes.NewReader(body))
	if err != nil {
		return consumererror.NewPermanent(err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+string(e.config.APIKey))
	response, err := e.client.Do(request)
	if err != nil {
		return fmt.Errorf("the ledger cannot be reached: %w", err)
	}
	defer response.Body.Close()
	answer, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
	switch {
	case response.StatusCode == http.StatusCreated || response.StatusCode == http.StatusOK:
		return nil
	case response.StatusCode == http.StatusTooManyRequests || response.StatusCode == http.StatusServiceUnavailable:
		err := fmt.Errorf("the ledger answered %d: %s", response.StatusCode, answer)
		if seconds, parseErr := strconv.Atoi(response.Header.Get("Retry-After")); parseErr == nil {
			return exporterhelper.NewThrottleRetry(err, time.Duration(seconds)*time.Second)
		}
		return err
	case response.StatusCode >= 400 && response.StatusCode < 500:
		e.settings.Logger.Warn("The ledger refused the evidence", zap.Int("status", response.StatusCode),
			zap.Int("entries", len(items)), zap.ByteString("answer", answer))
		return consumererror.NewPermanent(fmt.Errorf("the ledger answered %d: %s", response.StatusCode, answer))
	default:
		return fmt.Errorf("the ledger answered %d: %s", response.StatusCode, answer)
	}
}
