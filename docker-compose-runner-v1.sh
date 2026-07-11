#!/usr/bin/env bash
# Docker Compose Runner with Automatic Timezone Detection (Linux)
# Detects the host's IANA timezone, exports it for docker-compose,
# then runs build followed by up -d.

set -euo pipefail

# --- Colors ---
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

# --- Detect the host timezone (already IANA on Linux) ---
detect_timezone() {
    if [ -f /etc/timezone ]; then
        cat /etc/timezone
    elif [ -L /etc/localtime ]; then
        # e.g. /usr/share/zoneinfo/Europe/Kiev -> Europe/Kiev
        readlink -f /etc/localtime | sed 's#.*/zoneinfo/##'
    elif command -v timedatectl >/dev/null 2>&1; then
        timedatectl show --property=Timezone --value
    else
        echo ""
    fi
}

HOST_TIMEZONE="$(detect_timezone)"

if [ -z "$HOST_TIMEZONE" ]; then
    echo -e "${YELLOW}Could not detect timezone - defaulting to UTC${NC}"
    HOST_TIMEZONE="UTC"
fi

echo -e "${GREEN}Detected host timezone: ${HOST_TIMEZONE}${NC}"
export HOST_TIMEZONE

# --- Pick the docker compose command (v2 plugin vs legacy) ---
if docker compose version >/dev/null 2>&1; then
    DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    DC=(docker-compose)
else
    echo -e "${RED}Neither 'docker compose' nor 'docker-compose' is installed.${NC}"
    exit 1
fi

run_compose() {
    echo -e "\n${BLUE}Running: ${DC[*]} $*${NC}"
    "${DC[@]}" "$@"
}

# --- Build ---
echo -e "\n${CYAN}Building containers...${NC}"
if ! run_compose build; then
    echo -e "\n${RED}Build failed, containers were not started.${NC}"
    exit 1
fi

# --- Up ---
echo -e "\n${CYAN}Starting containers...${NC}"
run_compose up -d

echo -e "\n${GREEN}Containers are now running in the background with the correct timezone (${HOST_TIMEZONE})${NC}"
echo -e "${CYAN}To view logs: ${DC[*]} logs -f${NC}"
echo -e "${CYAN}To stop containers: ${DC[*]} down${NC}"
