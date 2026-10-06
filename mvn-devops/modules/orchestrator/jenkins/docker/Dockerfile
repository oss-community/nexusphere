FROM jenkins/jenkins:lts-jdk17

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends maven git openssh-client curl jq \
 && rm -rf /var/lib/apt/lists/*
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Everything (admin user, credentials, the pipeline job) comes from
# configuration as code, rendered by "devops.sh render" into casc_configs.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/var/jenkins_home/casc_configs
