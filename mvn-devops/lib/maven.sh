# shellcheck shell=bash
# Maven plugins the stages call directly.
#
# Stages run plugin goals by their full coordinates and configure them with
# -D properties, so the project's pom.xml needs no profiles, no
# distributionManagement and no plugin declarations.  A project that already
# configures one of these plugins in its pom keeps that configuration; command
# line properties only fill in what the pom leaves open.
#
# Override a version for one project with a value, e.g.
#   echo 3.11.1 > .devops/values/MVN_JAVADOC_VERSION

mvn_plugin() {
  local key=$1 coordinates=$2 default_version=$3 goal=$4
  printf '%s:%s:%s' "$coordinates" "$(value "$key" "$default_version")" "$goal"
}

mvn_source_jar()   { mvn_plugin MVN_SOURCE_VERSION org.apache.maven.plugins:maven-source-plugin 3.4.0 jar-no-fork; }
mvn_javadoc_jar()  { mvn_plugin MVN_JAVADOC_VERSION org.apache.maven.plugins:maven-javadoc-plugin 3.12.0 jar; }
mvn_checkstyle()   { mvn_plugin MVN_CHECKSTYLE_VERSION org.apache.maven.plugins:maven-checkstyle-plugin 3.6.0 check; }
mvn_deploy()       { mvn_plugin MVN_DEPLOY_VERSION org.apache.maven.plugins:maven-deploy-plugin 3.2.0 deploy; }
mvn_site()         { mvn_plugin MVN_SITE_VERSION org.apache.maven.plugins:maven-site-plugin 3.22.0 "$1"; }
mvn_scm_publish()  { mvn_plugin MVN_SCM_PUBLISH_VERSION org.apache.maven.plugins:maven-scm-publish-plugin 3.3.0 publish-scm; }
mvn_sonar()        { mvn_plugin MVN_SONAR_VERSION org.sonarsource.scanner.maven:sonar-maven-plugin 5.8.0.7211 sonar; }

# Goals that attach the sources and javadoc jars, when enabled in the build module.
mvn_attach_goals() {
  if [[ $(value MAVEN_ATTACH_SOURCES yes) =~ ^[Yy] ]]; then
    printf ' %s %s -Dmaven.javadoc.failOnError=false' "$(mvn_source_jar)" "$(mvn_javadoc_jar)"
  fi
}

# mvn_deploy_args <snapshot id> <snapshot url> <release id> <release url>
# Packages, attaches sources/javadoc and deploys to the given repositories.
# The ids must match <server> entries in templates/settings.xml.
mvn_deploy_args() {
  printf 'package -DskipTests=true%s %s -DaltSnapshotDeploymentRepository=%s::%s -DaltReleaseDeploymentRepository=%s::%s' \
    "$(mvn_attach_goals)" "$(mvn_deploy)" "$1" "$2" "$3" "$4"
}

mvn_versions_set() { mvn_plugin MVN_VERSIONS_VERSION org.codehaus.mojo:versions-maven-plugin 2.22.0 set; }
mvn_evaluate()     { mvn_plugin MVN_HELP_VERSION org.apache.maven.plugins:maven-help-plugin 3.5.2 evaluate; }

# Official Maven image with the project's Java, for pipelines run in containers.
maven_image() { printf 'maven:%s-eclipse-temurin-%s' "$(value MAVEN_VERSION 3.9)" "$(value JAVA_VERSION 21)"; }
