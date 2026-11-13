#!/usr/bin/env bash
set -euo pipefail
printf '=== Android 15 live-lab host preflight ===\n'
printf 'OS: '; . /etc/os-release; echo "$PRETTY_NAME"
printf 'vCPU: '; nproc
printf 'RAM: '; free -h | awk '/Mem:/{print $2}'
printf 'Free disk: '; df -h . | awk 'NR==2{print $4}'
if [[ -c /dev/kvm ]]; then echo 'KVM: yes'; else echo 'KVM: NO - required'; fi
if command -v docker >/dev/null 2>&1; then
  docker --version
  docker compose version || true
else
  echo 'Docker: missing'
fi
if command -v ss >/dev/null 2>&1; then
  echo 'Listeners on ports 80/443:'
  ss -lnt '( sport = :80 or sport = :443 )' || true
fi
printf '\nRecommended: 4+ vCPU, 12+ GB RAM, 40+ GB free disk, /dev/kvm, TCP 80/443.\n'
