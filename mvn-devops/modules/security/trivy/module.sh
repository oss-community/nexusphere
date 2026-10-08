# shellcheck shell=bash
# Scans the pushed image with Trivy (templates/scripts/scan-image.sh).  The
# Trivy binary is downloaded where the pipeline runs and its vulnerability
# database is cached next to it.

module_secrets() {
  require_image_module
  ask TRIVY_SEVERITY "Severities to list in the scan report" HIGH,CRITICAL
  ask TRIVY_FAIL_ON "Severities with a fix that fail the pipeline (empty: report only)" CRITICAL
}

module_env() {
  pipeline_var TRIVY_SEVERITY "$(value TRIVY_SEVERITY HIGH,CRITICAL)"
  pipeline_var TRIVY_FAIL_ON "$(value TRIVY_FAIL_ON)"
}

module_stages() {
  shell_stage 77 cd scan-image "sh \"\$DEVOPS_SCRIPTS/scan-image.sh\""
}
