# shellcheck shell=bash
# Writes the SBOM of the pushed image with Syft (templates/scripts/sbom.sh).
# With the cosign module the SBOM is also attested to the image.

module_secrets() {
  require_image_module
}

module_stages() {
  shell_stage 76 cd sbom "sh \"\$DEVOPS_SCRIPTS/sbom.sh\""
}
