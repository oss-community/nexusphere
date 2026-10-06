# shellcheck shell=bash
# Core Maven stages.  Plugins are called by their coordinates (lib/maven.sh),
# so the project's pom.xml needs no profiles for them.

module_secrets() {
  # One Java and Maven version for every orchestrator: the Jenkins image, the
  # Concourse task image, and what doctor expects on this machine.
  ask JAVA_VERSION "Java version for builds (21 also builds projects that target 17)" 21
  ask MAVEN_VERSION "Maven version for builds" 3.9
  ask MAVEN_SETTINGS "Extra settings file of the project, relative to it (empty: none)" ""
  ask MAVEN_PROFILES "Extra project profiles for every stage (empty: none)" ""
  ask MAVEN_ATTACH_SOURCES "Attach sources and javadoc jars when deploying? yes/no" yes
  ask MAVEN_CHECKSTYLE "Run checkstyle? yes/no" yes
  ask MAVEN_CHECKSTYLE_CONFIG "Checkstyle rules: google_checks.xml, sun_checks.xml or a file in the project" google_checks.xml
}

checkstyle_config() {
  local config
  config=$(value MAVEN_CHECKSTYLE_CONFIG google_checks.xml)
  case $config in
    google_checks.xml|sun_checks.xml|http://*|https://*) printf '%s' "$config" ;;
    # Absolute, so every module of a multi-module build finds it.
    *) printf '$PWD/%s' "$config" ;;
  esac
}

module_stages() {
  stage 10 ci validate "validate"
  stage 20 ci build "clean package -DskipTests=true"
  stage 30 ci test "test"
  if [[ $(value MAVEN_CHECKSTYLE yes) =~ ^[Yy] ]]; then
    stage 40 ci checkstyle "$(mvn_checkstyle) -Dcheckstyle.config.location=$(checkstyle_config)"
  fi
  stage 50 ci install "install -DskipTests=true"
}
