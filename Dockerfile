FROM eclipse-temurin:21-jdk-alpine

ARG JAR_PATH=./core/bootstrap/target
ARG JAR_NAME=bootstrap
ARG JAR_VERSION=1.0.0-SNAPSHOT
ARG TARGET_PATH=/app

RUN apk --no-cache add ca-certificates wget curl

ENV APPLICATION=${TARGET_PATH}/application.jar
ENV APP_HOST=0.0.0.0
ENV APP_PORT=8080
ENV APP_PROFILES=postgresql
ENV APP_DATABASE_HOST=localhost
ENV APP_DATABASE_PORT=5432
ENV APP_DATABASE_DB=nexusphere
ENV APP_DATABASE_USERNAME=nexusphere
ENV APP_DATABASE_PASSWORD=nexusphere
ENV APP_TOKEN_ISSUER=nexusphere
ENV APP_TOKEN_SECRET=nexusphere-development-token-secret-change-me
ENV APP_TOKEN_TTL=15m
ENV APP_OPERATOR_SECRET=nexusphere-development-operator-secret-change-me

ADD ${JAR_PATH}/${JAR_NAME}-${JAR_VERSION}-exec.jar ${TARGET_PATH}/application.jar

EXPOSE ${APP_PORT}
ENTRYPOINT java -jar ${APPLICATION}
