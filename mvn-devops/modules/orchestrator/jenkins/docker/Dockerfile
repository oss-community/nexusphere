# Jenkins with the same Java and Maven as the other orchestrators:
# Maven comes from the official maven image, not from the Debian packages.
ARG JAVA_VERSION=21
ARG MAVEN_VERSION=3.9
FROM maven:${MAVEN_VERSION}-eclipse-temurin-${JAVA_VERSION} AS maven

FROM jenkins/jenkins:lts-jdk${JAVA_VERSION}

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends git openssh-client curl jq \
 && rm -rf /var/lib/apt/lists/*
COPY --from=maven /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
ENV MAVEN_HOME=/usr/share/maven
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Everything (admin user, credentials, the pipeline job) comes from
# configuration as code, rendered by "devops.sh render" into casc_configs.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/var/jenkins_home/casc_configs
