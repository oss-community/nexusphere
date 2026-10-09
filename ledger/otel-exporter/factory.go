package nexusphereexporter

import (
	"context"
	"time"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/config/confighttp"
	"go.opentelemetry.io/collector/config/configoptional"
	"go.opentelemetry.io/collector/config/configretry"
	"go.opentelemetry.io/collector/consumer"
	"go.opentelemetry.io/collector/exporter"
	"go.opentelemetry.io/collector/exporter/exporterhelper"
)

const (
	typeName  = "nexusphere"
	stability = component.StabilityLevelBeta
	maxBatch  = 500
)

var componentType = component.MustNewType(typeName)

func NewFactory() exporter.Factory {
	return exporter.NewFactory(componentType, createDefaultConfig, exporter.WithTraces(createTraces, stability))
}

func createDefaultConfig() component.Config {
	client := confighttp.NewDefaultClientConfig()
	client.Timeout = 30 * time.Second
	return &Config{
		ClientConfig:        client,
		QueueConfig:         configoptional.Some(exporterhelper.NewDefaultQueueConfig()),
		RetryConfig:         configretry.NewDefaultBackOffConfig(),
		Operations:          []string{"execute_tool", "invoke_agent", "tools/call"},
		AgentAttributes:     []string{"gen_ai.agent.id", "gen_ai.agent.name"},
		PrincipalAttributes: []string{"nexusphere.principal.id", "user.id", "enduser.id"},
		HashContent:         true,
		BatchSize:           maxBatch,
	}
}

func createTraces(ctx context.Context, set exporter.Settings, cfg component.Config) (exporter.Traces, error) {
	config := cfg.(*Config)
	e := newLedgerExporter(config, set)
	return exporterhelper.NewTraces(ctx, set, cfg, e.pushTraces,
		exporterhelper.WithStart(e.start),
		exporterhelper.WithCapabilities(consumer.Capabilities{MutatesData: false}),
		exporterhelper.WithTimeout(exporterhelper.TimeoutConfig{Timeout: 0}),
		exporterhelper.WithRetry(config.RetryConfig),
		exporterhelper.WithQueue(config.QueueConfig),
	)
}
