FROM golang:1.26-alpine AS build

ARG BUILDER_VERSION=v0.162.0

WORKDIR /src
COPY . .
RUN go install go.opentelemetry.io/collector/cmd/builder@${BUILDER_VERSION}
RUN cd collector && CGO_ENABLED=0 builder --config builder-config.yaml

FROM alpine:3.22

COPY --from=build /src/collector/_build/nexusphere-otelcol /otelcol/nexusphere-otelcol
COPY collector/config.yaml /otelcol/config.yaml

USER 10001
EXPOSE 4317 4318
ENTRYPOINT ["/otelcol/nexusphere-otelcol"]
CMD ["--config", "/otelcol/config.yaml"]
