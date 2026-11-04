#!/bin/sh
# ResoShare database backup loop (runs in the `backup` container, see docker-compose.yml).
#
#  - pg_dump -Fc every day at BACKUP_AT (Asia/Dhaka, default 03:00) → $BACKUP_DIR/resoshare-YYYYmmdd-HHMM.dump
#  - also immediately when $BACKUP_DIR/.request exists (written by the API's POST /admin/backup; deleted here)
#  - deletes dumps older than KEEP_DAYS (default 14)
#  - keeps $BACKUP_DIR/status.json = {lastAt, status, sizeBytes, keepDays} up to date (atomic rename)
#  - checks every 30 s. RUN_ONCE=1 runs a single check and exits (used by tests).
#
# Connection comes from the standard libpq env vars: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
# Dumps contain personal data: they are created mode 600 and the directory is not listable by the API.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
BACKUP_AT="${BACKUP_AT:-03:00}"
INTERVAL="${CHECK_INTERVAL_SEC:-30}"
RETRY_AFTER_FAIL_SEC="${RETRY_AFTER_FAIL_SEC:-1800}" # a failed daily run is retried after 30 min, not every 30 s
DHAKA_OFFSET_SEC=21600 # Asia/Dhaka is UTC+6, no DST

case "$KEEP_DAYS" in '' | *[!0-9]*) echo "KEEP_DAYS must be a number" >&2; exit 2 ;; esac
case "$BACKUP_AT" in [0-2][0-9]:[0-5][0-9]) ;; *) echo "BACKUP_AT must be HH:MM" >&2; exit 2 ;; esac

umask 077
mkdir -p "$BACKUP_DIR"
# Owner (this container) full access; others (the API user) may only create files and open them by name:
# they can write .request and read status.json, but cannot list or delete the dumps (sticky bit).
chmod 1733 "$BACKUP_DIR" 2>/dev/null || true

dhaka() { date -u -d "@$(( $(date -u +%s) + DHAKA_OFFSET_SEC ))" "+$1"; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

LAST_AT=null
LAST_SIZE=null
LAST_FAIL_EPOCH=0
if [ -f "$BACKUP_DIR/status.json" ]; then
  v=$(sed -nE 's/.*"lastAt":("[0-9TZ:.-]+"|null).*/\1/p' "$BACKUP_DIR/status.json" || true)
  [ -n "$v" ] && LAST_AT="$v"
  v=$(sed -nE 's/.*"sizeBytes":([0-9]+|null).*/\1/p' "$BACKUP_DIR/status.json" || true)
  [ -n "$v" ] && LAST_SIZE="$v"
fi

write_status() { # $1 status
  tmp="$BACKUP_DIR/.status.json.tmp"
  printf '{"lastAt":%s,"status":"%s","sizeBytes":%s,"keepDays":%s}\n' "$LAST_AT" "$1" "$LAST_SIZE" "$KEEP_DAYS" > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$BACKUP_DIR/status.json"
}

latest_dump() {
  # newest dump name (names sort chronologically); empty when none
  ls -1 "$BACKUP_DIR" 2>/dev/null | grep -E '^resoshare-[0-9]{8}-[0-9]{4}\.dump$' | sort | tail -n 1 || true
}

run_backup() {
  write_status running
  stamp=$(dhaka %Y%m%d-%H%M)
  out="$BACKUP_DIR/resoshare-$stamp.dump"
  part="$out.partial"
  if pg_dump -Fc --no-owner --no-privileges -f "$part"; then
    chmod 600 "$part"
    mv -f "$part" "$out"
    LAST_AT="\"$(now_iso)\""
    LAST_SIZE=$(wc -c < "$out" | tr -d ' ')
    write_status ok
    echo "$(now_iso) backup ok: $(basename "$out") ($LAST_SIZE bytes)"
  else
    rm -f "$part"
    LAST_AT="\"$(now_iso)\""
    LAST_SIZE=null
    LAST_FAIL_EPOCH=$(date -u +%s)
    write_status failed
    echo "$(now_iso) backup FAILED" >&2
  fi
  # retention
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'resoshare-*.dump' -mtime +"$KEEP_DAYS" -exec rm -f {} \;
}

daily_due() {
  [ $(( $(date -u +%s) - LAST_FAIL_EPOCH )) -ge "$RETRY_AFTER_FAIL_SEC" ] || return 1
  hhmm=$(dhaka %H%M)
  at=$(echo "$BACKUP_AT" | tr -d ':')
  [ "1$hhmm" -ge "1$at" ] || return 1 # "1" prefix: no leading-zero ambiguity
  cutoff="resoshare-$(dhaka %Y%m%d)-$at.dump"
  latest=$(latest_dump)
  [ -z "$latest" ] && return 0
  # due when the newest dump is older than today's scheduled time
  first=$(printf '%s\n%s\n' "$latest" "$cutoff" | sort | head -n 1)
  [ "$first" = "$latest" ] && [ "$latest" != "$cutoff" ]
}

check_once() {
  if [ -f "$BACKUP_DIR/.request" ]; then
    rm -f "$BACKUP_DIR/.request" # consume first: a new request during the dump queues another run
    run_backup
  elif daily_due; then
    run_backup
  fi
}

if [ "${RUN_ONCE:-0}" = 1 ]; then
  check_once
  exit 0
fi

echo "$(now_iso) backup loop started (daily at $BACKUP_AT Asia/Dhaka, keep $KEEP_DAYS days)"
while :; do
  check_once || echo "$(now_iso) backup check error" >&2
  sleep "$INTERVAL"
done
