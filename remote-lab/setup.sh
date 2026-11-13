#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")"

ram_kb=$(awk '/MemTotal:/ {print $2}' /proc/meminfo)
free_kb=$(df -Pk . | awk 'END {print $4}')
if (( $(nproc) < 4 || ram_kb < 8 * 1024 * 1024 || free_kb < 30 * 1024 * 1024 )); then
  echo 'ERROR: Need 4+ vCPU, 8+ GiB RAM and 30+ GiB free disk (12 GiB RAM and 40+ GiB disk recommended).' >&2
  echo 'Stopping before modifying existing services.' >&2
  exit 1
fi
if [[ ! -c /dev/kvm ]]; then
  echo 'ERROR: /dev/kvm is absent. This host cannot run accelerated Android 15 emulators.' >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo 'ERROR: Docker Engine and Docker Compose v2 are required.' >&2
  exit 1
fi
if [[ -f .env ]]; then
  echo 'ERROR: Existing .env found; refusing to overwrite domain/password.' >&2
  exit 1
fi
if command -v ss >/dev/null 2>&1; then
  if ss -lntH '( sport = :80 or sport = :443 )' | grep -q .; then
    echo 'ERROR: TCP 80/443 already in use; refusing to interrupt existing services.' >&2
    exit 1
  fi
fi

printf 'Enter your dedicated DNS hostname (e.g. lab.example.com): '
read -r domain
if [[ ! "$domain" =~ ^[a-zA-Z0-9.-]+$ || "$domain" != *.* ]]; then
  echo 'ERROR: Provide a valid DNS hostname, not an IP or URL.' >&2
  exit 1
fi
read -r -s -p 'Choose a private viewing password (14+ characters): ' password
printf '\n'
if (( ${#password} < 14 )); then
  echo 'ERROR: Password must be at least 14 characters.' >&2
  exit 1
fi
hash=$(docker run --rm caddy:2.10 caddy hash-password --algorithm bcrypt --plaintext "$password")
unset password
{
  printf 'VIEW_DOMAIN=%s\n' "$domain"
  printf "VIEW_PASSWORD_HASH='%s'\n" "$hash"
} > .env
chmod 600 .env
printf 'Configuration created; building two Android emulators and starting HTTPS gateway.\n'
docker compose up --build -d
printf '\nBrowser: https://%s/\nUser: viewer\nPassword: the password you just entered.\n' "$domain"
printf 'If HTTPS is unavailable, check DNS and public TCP 80/443 ingress.\n'
