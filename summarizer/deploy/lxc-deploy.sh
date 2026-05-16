#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Summarize — LXC Native Deployment Script
# =============================================================================
# Deploys summarize-daemon and summarize-api on a Debian/Ubuntu LXC container
# without Docker. Both services run as a dedicated system user under systemd.
#
# Usage:
#   ./lxc-deploy.sh              # Full install
#   ./lxc-deploy.sh install      # Full install (explicit)
#   ./lxc-deploy.sh update       # Pull latest code, rebuild, restart
#   ./lxc-deploy.sh uninstall    # Stop services and remove installation
#   ./lxc-deploy.sh status       # Show service status
# =============================================================================

readonly APP_USER="summarize"
readonly APP_HOME="/opt/summarize"
readonly DAEMON_DIR="${APP_HOME}/daemon"
readonly API_DIR="${APP_HOME}/api"
readonly DATA_DIR="${API_DIR}/data"
readonly CONFIG_DIR="/etc/summarize"
readonly ENV_FILE="${CONFIG_DIR}/summarize.env"
readonly DAEMON_CONFIG_DIR="${APP_HOME}/.summarize"
readonly DAEMON_REPO="https://github.com/steipete/summarize.git"

# Resolve the directory this script lives in
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

log() {
    printf '[summarize] %s\n' "$*"
}

error() {
    printf '[summarize] ERROR: %s\n' "$*" >&2
}

die() {
    error "$@"
    exit 1
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        die "This script must be run as root."
    fi
}

check_os() {
    if [[ ! -f /etc/os-release ]]; then
        die "Cannot detect OS. /etc/os-release not found."
    fi
    # shellcheck source=/dev/null
    . /etc/os-release
    case "${ID:-}" in
        debian|ubuntu) ;;
        *) die "Unsupported OS: ${ID:-unknown}. This script requires Debian or Ubuntu." ;;
    esac
    log "Detected OS: ${PRETTY_NAME:-${ID}}"
}

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------

install_system_deps() {
    log "Installing system dependencies..."
    apt-get update -qq
    apt-get install -y --no-install-recommends \
        ffmpeg python3 python3-pip curl git build-essential ca-certificates gnupg

    log "Installing Node.js 22 via NodeSource..."
    if ! command -v node &>/dev/null || ! node --version | grep -q "^v22"; then
        curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
        apt-get install -y nodejs
    else
        log "Node.js 22 already installed: $(node --version)"
    fi

    log "Enabling pnpm via corepack..."
    corepack enable
    corepack prepare pnpm@latest --activate

    log "Installing yt-dlp..."
    curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp
    chmod +x /usr/local/bin/yt-dlp
}

create_user() {
    if id "${APP_USER}" &>/dev/null; then
        log "User '${APP_USER}' already exists."
    else
        log "Creating system user '${APP_USER}'..."
        useradd --system --home "${APP_HOME}" --create-home --shell /usr/sbin/nologin "${APP_USER}"
    fi
}

install_daemon() {
    log "Setting up summarize daemon..."
    if [[ -d "${DAEMON_DIR}/.git" ]]; then
        log "Updating existing daemon clone..."
        git -C "${DAEMON_DIR}" pull --ff-only
    else
        log "Cloning steipete/summarize..."
        rm -rf "${DAEMON_DIR}"
        git clone --depth 1 "${DAEMON_REPO}" "${DAEMON_DIR}"
    fi

    log "Installing daemon dependencies..."
    cd "${DAEMON_DIR}"
    pnpm install --frozen-lockfile
    log "Building daemon..."
    pnpm build

    # Create daemon config directory (daemon reads ~/.summarize/daemon.json)
    mkdir -p "${DAEMON_CONFIG_DIR}/logs"
    chown -R "${APP_USER}:${APP_USER}" "${DAEMON_CONFIG_DIR}"
    chown -R "${APP_USER}:${APP_USER}" "${DAEMON_DIR}"
}

install_api() {
    log "Setting up summarize API..."
    local api_src="${REPO_ROOT}/summarize-api"

    if [[ ! -d "${api_src}" ]]; then
        die "Cannot find summarize-api source at ${api_src}"
    fi

    mkdir -p "${API_DIR}"

    # Copy source files (preserve structure, exclude node_modules and dist)
    log "Copying API source..."
    rsync -a --delete \
        --exclude='node_modules' \
        --exclude='dist' \
        --exclude='.turbo' \
        "${api_src}/" "${API_DIR}/"

    log "Installing API dependencies..."
    cd "${API_DIR}"
    pnpm install --frozen-lockfile
    log "Building API..."
    pnpm build

    # Create data directories
    mkdir -p "${DATA_DIR}/uploads"
    chown -R "${APP_USER}:${APP_USER}" "${API_DIR}"
}

install_config() {
    mkdir -p "${CONFIG_DIR}"

    if [[ -f "${ENV_FILE}" ]]; then
        log "Config file ${ENV_FILE} already exists — not overwriting."
    else
        log "Installing environment template to ${ENV_FILE}..."
        cp "${SCRIPT_DIR}/summarize.env" "${ENV_FILE}"
        chmod 600 "${ENV_FILE}"
        chown root:root "${ENV_FILE}"
    fi
}

install_systemd_units() {
    log "Installing systemd units..."

    # Write the daemon-config helper script
    install_daemon_config_helper

    cp "${SCRIPT_DIR}/summarize-daemon.service" /etc/systemd/system/
    cp "${SCRIPT_DIR}/summarize-api.service" /etc/systemd/system/
    systemctl daemon-reload
}

install_daemon_config_helper() {
    # The upstream daemon reads config from ~/.summarize/daemon.json
    # Write a helper that generates the config before the daemon starts
    cat > "${APP_HOME}/write-daemon-config.sh" <<'HELPER'
#!/usr/bin/env bash
set -euo pipefail
# Generates daemon.json from environment variables.
# Called by systemd ExecStartPre.
CONFIG_DIR="${HOME}/.summarize"
mkdir -p "${CONFIG_DIR}/logs"
cat > "${CONFIG_DIR}/daemon.json" <<EOF
{
  "version": 1,
  "token": "${SUMMARIZE_TOKEN}",
  "port": ${DAEMON_PORT:-8787},
  "installedAt": "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
}
EOF
HELPER
    chmod +x "${APP_HOME}/write-daemon-config.sh"
    chown "${APP_USER}:${APP_USER}" "${APP_HOME}/write-daemon-config.sh"
}

enable_services() {
    log "Enabling and starting services..."
    systemctl enable --now summarize-daemon summarize-api

    log "Waiting for services to start..."
    sleep 3
}

print_status() {
    local daemon_status api_status
    daemon_status="$(systemctl is-active summarize-daemon 2>/dev/null || true)"
    api_status="$(systemctl is-active summarize-api 2>/dev/null || true)"

    log "============================================"
    log "Services:"
    log "  summarize-daemon: ${daemon_status}"
    log "  summarize-api:    ${api_status}"
    log "============================================"
}

print_install_summary() {
    local daemon_status api_status
    daemon_status="$(systemctl is-active summarize-daemon 2>/dev/null || true)"
    api_status="$(systemctl is-active summarize-api 2>/dev/null || true)"

    log "============================================"
    log "Installation complete!"
    log ""
    log "Services:"
    log "  summarize-daemon: ${daemon_status}"
    log "  summarize-api:    ${api_status}"
    log ""
    log "Next steps:"
    log "  1. Edit ${ENV_FILE}"
    log "     - Set API_KEYS (required)"
    log "     - Set SUMMARIZE_TOKEN (required)"
    log "     - Set OPENAI_API_KEY or other LLM keys"
    log "  2. Restart: systemctl restart summarize-daemon summarize-api"
    log "  3. Test: curl http://localhost:3000/health"
    log "============================================"
}

do_install() {
    check_root
    check_os
    install_system_deps
    create_user
    install_daemon
    install_api
    install_config
    install_systemd_units
    enable_services
    print_install_summary
}

# ---------------------------------------------------------------------------
# Update
# ---------------------------------------------------------------------------

do_update() {
    check_root
    log "Updating summarize installation..."

    install_daemon
    install_api

    log "Restarting services..."
    systemctl restart summarize-daemon summarize-api

    sleep 3
    print_status
    log "Update complete."
}

# ---------------------------------------------------------------------------
# Uninstall
# ---------------------------------------------------------------------------

do_uninstall() {
    check_root

    read -rp "[summarize] Are you sure you want to uninstall? [y/N] " confirm
    case "${confirm}" in
        [yY]|[yY][eE][sS]) ;;
        *) log "Aborted."; exit 0 ;;
    esac

    log "Stopping and disabling services..."
    systemctl stop summarize-daemon summarize-api 2>/dev/null || true
    systemctl disable summarize-daemon summarize-api 2>/dev/null || true

    log "Removing systemd units..."
    rm -f /etc/systemd/system/summarize-daemon.service
    rm -f /etc/systemd/system/summarize-api.service
    systemctl daemon-reload

    read -rp "[summarize] Remove ${APP_HOME} and ${CONFIG_DIR}? [y/N] " confirm_data
    case "${confirm_data}" in
        [yY]|[yY][eE][sS])
            log "Removing ${APP_HOME}..."
            rm -rf "${APP_HOME}"
            log "Removing ${CONFIG_DIR}..."
            rm -rf "${CONFIG_DIR}"
            ;;
        *)
            log "Kept ${APP_HOME} and ${CONFIG_DIR}."
            ;;
    esac

    log "Uninstall complete."
}

# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

do_status() {
    log "Service status:"
    echo ""
    systemctl status summarize-daemon --no-pager 2>/dev/null || true
    echo ""
    systemctl status summarize-api --no-pager 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    local command="${1:-install}"

    case "${command}" in
        install)   do_install ;;
        update)    do_update ;;
        uninstall) do_uninstall ;;
        status)    do_status ;;
        *)
            echo "Usage: $0 {install|update|uninstall|status}"
            exit 1
            ;;
    esac
}

main "$@"
