package nexusphereexporter

import (
	"errors"

	"go.opentelemetry.io/collector/config/confighttp"
	"go.opentelemetry.io/collector/config/configopaque"
	"go.opentelemetry.io/collector/config/configoptional"
	"go.opentelemetry.io/collector/config/configretry"
	"go.opentelemetry.io/collector/exporter/exporterhelper"
)

type Config struct {
	ClientConfig        confighttp.ClientConfig                                  `mapstructure:",squash"`
	QueueConfig         configoptional.Optional[exporterhelper.QueueBatchConfig] `mapstructure:"sending_queue"`
	RetryConfig         configretry.BackOffConfig                                `mapstructure:"retry_on_failure"`
	APIKey              configopaque.String                                      `mapstructure:"api_key"`
	Operations          []string                                                 `mapstructure:"operations"`
	AgentAttributes     []string                                                 `mapstructure:"agent_attributes"`
	DefaultAgent        string                                                   `mapstructure:"default_agent"`
	PrincipalAttributes []string                                                 `mapstructure:"principal_attributes"`
	DefaultPrincipal    string                                                   `mapstructure:"default_principal"`
	Attributes          []string                                                 `mapstructure:"attributes"`
	HashContent         bool                                                     `mapstructure:"hash_content"`
	BatchSize           int                                                      `mapstructure:"batch_size"`
}

func (c *Config) Validate() error {
	if c.ClientConfig.Endpoint == "" {
		return errors.New("endpoint is required: the base URL of the ledger, such as http://localhost:8090")
	}
	if c.APIKey == "" {
		return errors.New("api_key is required: an agent API key or the operator key of the ledger")
	}
	if len(c.Operations) == 0 {
		return errors.New("operations must name at least one gen_ai.operation.name or mcp.method.name")
	}
	if c.BatchSize < 1 || c.BatchSize > maxBatch {
		return errors.New("batch_size must be between 1 and 500")
	}
	return nil
}
