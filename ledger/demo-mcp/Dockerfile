FROM eclipse-temurin:21-jre-alpine

ARG JAR_PATH=./target
ARG JAR_NAME=demo-mcp
ARG JAR_VERSION=1.0.0-SNAPSHOT
ARG TARGET_PATH=/app

ENV APPLICATION=${TARGET_PATH}/demo-mcp.jar
ENV DEMO_MCP_HOST=0.0.0.0
ENV DEMO_MCP_PORT=8091

ADD ${JAR_PATH}/${JAR_NAME}-${JAR_VERSION}-exec.jar ${TARGET_PATH}/demo-mcp.jar

EXPOSE ${DEMO_MCP_PORT}
HEALTHCHECK --interval=5s --timeout=3s --retries=20 CMD wget -qO- http://localhost:${DEMO_MCP_PORT}/health || exit 1
ENTRYPOINT java -jar ${APPLICATION}
