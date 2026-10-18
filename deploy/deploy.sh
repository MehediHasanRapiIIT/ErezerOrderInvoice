#!/usr/bin/env bash
# ============================================================================
# EREZER admin panel - VPS-side deploy
#
# Runs ON THE VPS, invoked over SSH by .github/workflows/cd.yml after it has
# copied the compose files across. Safe to run by hand too:
#
#   IMAGE_TAG=1a2b3c4 IMAGE_REPO_OWNER=youruser /opt/erezer-invoice/deploy/deploy.sh
#
# It guarantees:
#   * the exact image tag CI built is what starts
#   * a failed rollout rolls back to the previous tag by itself
#   * a non-zero exit when the stack is not healthy, so GitHub shows red
#   * the shop's Caddy routes PANEL_HOST here (site file + validated reload)
# ============================================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/erezer-invoice}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
STATE_FILE="$APP_DIR/.deployed-tag"
EDGE_NETWORK=erezer-edge

COMPOSE=(docker compose -f compose.yaml -f compose.prod.yaml -f compose.deploy.yaml)
# minio-init is a one-shot job; the rest must be healthy for a rollout to count.
HEALTH_GATED=(postgres minio backend frontend)

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

cd "$APP_DIR" || die "$APP_DIR does not exist - see DEPLOYMENT.md step 3"
[[ -f .env ]] || die ".env is missing in $APP_DIR (copy .env.prod.example to .env and fill it in)"

# A Docker Hub login of its own, if one was made with
#   DOCKER_CONFIG=/opt/erezer-invoice/.docker docker login -u <account>
# so a different account from the shop's never replaces the shop's login (~/.docker).
if [[ -f "$APP_DIR/.docker/config.json" ]]; then
  export DOCKER_CONFIG="$APP_DIR/.docker"
fi

: "${IMAGE_TAG:?IMAGE_TAG must be set (the git SHA CI pushed)}"
: "${IMAGE_REPO_OWNER:?IMAGE_REPO_OWNER must be set (your Docker Hub account)}"
export IMAGE_TAG IMAGE_REPO_OWNER

# Settings the deploy itself needs, read from .env without sourcing the whole file.
env_value() { grep -E "^$1=" .env | tail -n1 | cut -d= -f2- | tr -d '"' | tr -d "'" ; }
PANEL_HOST="$(env_value PANEL_HOST)"
CADDY_SITES_DIR="$(env_value CADDY_SITES_DIR)"; CADDY_SITES_DIR="${CADDY_SITES_DIR:-/opt/erezer/sites}"
CADDY_CONTAINER="$(env_value CADDY_CONTAINER)"; CADDY_CONTAINER="${CADDY_CONTAINER:-erezer-caddy}"
[[ "$PANEL_HOST" =~ ^[a-z0-9.-]+$ ]] || die "PANEL_HOST in .env must be a bare hostname like erezer.site (no https://)"

PREVIOUS_TAG="$(cat "$STATE_FILE" 2>/dev/null || true)"

wait_for_health() {
  local service="$1" deadline=$((SECONDS + HEALTH_TIMEOUT)) cid state
  while (( SECONDS < deadline )); do
    cid="$("${COMPOSE[@]}" ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
      case "$state" in
        healthy)     printf '    %-10s %s\n' "$service" "$state"; return 0 ;;
        exited|dead) printf '    %-10s %s\n' "$service" "$state"; return 1 ;;
      esac
    fi
    sleep 5
  done
  printf '    %-10s TIMED OUT after %ss\n' "$service" "$HEALTH_TIMEOUT"
  return 1
}

rollback() {
  if [[ -z "$PREVIOUS_TAG" || "$PREVIOUS_TAG" == "$IMAGE_TAG" ]]; then
    warn "no previous tag recorded - leaving the stack as it is for inspection"
    return
  fi
  log "Rolling back to $PREVIOUS_TAG"
  if IMAGE_TAG="$PREVIOUS_TAG" "${COMPOSE[@]}" up -d --remove-orphans; then
    warn "rolled back to $PREVIOUS_TAG"
  else
    warn "ROLLBACK FAILED - the stack needs manual attention"
  fi
}

log "Deploying ${IMAGE_REPO_OWNER}/erezer-invoice-*:${IMAGE_TAG}  (previous: ${PREVIOUS_TAG:-none})"

# The network the shop's Caddy and this panel share. Harmless if it already exists.
docker network inspect "$EDGE_NETWORK" >/dev/null 2>&1 || {
  log "Creating the shared network $EDGE_NETWORK"
  docker network create "$EDGE_NETWORK" >/dev/null
}

log "Pulling images"
"${COMPOSE[@]}" pull --quiet backend frontend \
  || die "pull failed - check the tag exists and, for private repos, that this VPS is logged in (docker login)"

log "Starting the stack"
"${COMPOSE[@]}" up -d --remove-orphans

log "Waiting for health (up to ${HEALTH_TIMEOUT}s per service)"
failed=()
for service in "${HEALTH_GATED[@]}"; do
  wait_for_health "$service" || failed+=("$service")
done
if (( ${#failed[@]} > 0 )); then
  warn "unhealthy: ${failed[*]}"
  for service in "${failed[@]}"; do
    printf '\n--- last 60 log lines: %s ---\n' "$service"
    "${COMPOSE[@]}" logs --tail=60 --no-color "$service" 2>&1 || true
  done
  rollback
  die "deploy of $IMAGE_TAG failed"
fi
echo "$IMAGE_TAG" > "$STATE_FILE"

# ── Route PANEL_HOST through the shop's Caddy ───────────────────────────────
log "Updating the Caddy site for $PANEL_HOST"
mkdir -p "$CADDY_SITES_DIR"
site_file="$CADDY_SITES_DIR/erezer-invoice.caddy"
rendered="$(sed "s/__PANEL_HOST__/$PANEL_HOST/g" deploy/caddy/erezer-invoice.caddy.template)"
if [[ "$rendered" != "$(cat "$site_file" 2>/dev/null || true)" ]]; then
  if ! docker inspect "$CADDY_CONTAINER" >/dev/null 2>&1; then
    warn "Caddy container '$CADDY_CONTAINER' not found - the panel runs but is not reachable from the internet yet."
    warn "Deploy the shop first (it must include the shared-sites change, see DEPLOYMENT.md step 2)."
    printf '%s\n' "$rendered" > "$site_file"
  else
    backup="$(cat "$site_file" 2>/dev/null || true)"
    printf '%s\n' "$rendered" > "$site_file"
    # `caddy reload` validates first and keeps the running config if anything is wrong.
    if docker exec "$CADDY_CONTAINER" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; then
      echo "    Caddy reloaded"
    else
      if [[ -n "$backup" ]]; then printf '%s\n' "$backup" > "$site_file"; else rm -f "$site_file"; fi
      die "Caddy rejected the new site file - restored the previous one; the panel itself is deployed"
    fi
  fi
else
  echo "    unchanged"
fi

log "Reclaiming disk from superseded images"
docker image prune -f >/dev/null || true

log "Deployed $IMAGE_TAG successfully"
"${COMPOSE[@]}" ps
