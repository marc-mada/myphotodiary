#!/bin/bash
# Starts the backend and Caddy side by side, and stops the container as
# soon as either one exits, so Docker's restart policy brings both back.
#
# HTTPS (one of three modes):
#   MPD_DOMAIN=photos.example.com   automatic Let's Encrypt certificate for
#                                   that public name (ports 80/443 must be
#                                   reachable from the Internet). Several
#                                   names: separate them with spaces.
#                                   MPD_ACME_EMAIL (optional) is given to
#                                   Let's Encrypt for expiry notices.
#                                   MPD_ACME_CA=staging uses Let's Encrypt's
#                                   test service (untrusted certificates,
#                                   no rate limits) to check a setup first;
#                                   a full ACME directory URL also works.
#   (no MPD_DOMAIN)                 a local certificate from Caddy's own
#                                   authority, for MPD_LOCAL_HOSTNAMES
#                                   (default "localhost"; add the machine's
#                                   IP or LAN name). Browsers warn until that
#                                   authority is trusted.
#   MPD_HTTPS=off                   plain HTTP on port 80, ONLY behind
#                                   another web server that does HTTPS (the
#                                   sign-in cookie requires HTTPS).

set -euo pipefail

for dir in db images backup tmp config caddy; do
	if ! mkdir -p "/data/$dir" 2>/dev/null || [ ! -w "/data/$dir" ]; then
		echo "myphotodiary: /data/$dir is not writable by user $(id -u):$(id -g)." >&2
		echo "  Give that user ownership of the host folder mounted there, or start the container with --user." >&2
		exit 1
	fi
done
# Batch Publish only reads the staging folder (a camera card or a disk can
# be mounted read-only there), so it only has to be readable.
mkdir -p /data/staging 2>/dev/null || true
if [ ! -r /data/staging ] || [ ! -x /data/staging ]; then
	echo "myphotodiary: /data/staging is not readable by user $(id -u):$(id -g)." >&2
	exit 1
fi

# Welcome photo - first start only, i.e. no database yet. Once the database
# exists it's never copied again, so it can be replaced or deleted. Also
# skipped when the photo folder already holds anything, files or folders
# (an existing library).
db_path="${MPD_DB_PATH:-/data/db/myphotodiary}"
storage_root="${MPD_STORAGE_ROOT:-/data/images}"
if [ ! -e "${db_path}.properties" ] && [ ! -e "${db_path}.script" ] \
	&& [ -d /opt/myphotodiary/welcome ] && [ -d "$storage_root" ] \
	&& [ -z "$(find "$storage_root" -mindepth 1 -maxdepth 1 ! -name '.*' | head -1)" ]; then
	for f in /opt/myphotodiary/welcome/*; do
		if [ -f "$f" ]; then
			cp "$f" "$storage_root/"
		fi
	done
fi

domain="${MPD_DOMAIN:-}"
https_mode="${MPD_HTTPS:-on}"

CADDY_GLOBAL_OPTIONS=""
CADDY_TLS=""
if [ "$https_mode" = "off" ]; then
	CADDY_SITE_ADDRESS="http://"
	# The web server in front (e.g. the host's own Caddy) reaches the container from a
	# local or private address; trust the X-Forwarded-Proto/Host it sends,
	# so the app knows visitors really use https.
	CADDY_GLOBAL_OPTIONS="auto_https off
	servers {
		trusted_proxies static private_ranges
	}"
	mode_text="HTTPS off (plain HTTP on port 80, behind another HTTPS web server)"
elif [ -n "$domain" ]; then
	# Caddy wants ", " (comma AND space) between several site addresses.
	CADDY_SITE_ADDRESS="$(echo "$domain" | xargs | sed 's/ /, /g')"
	acme_ca="${MPD_ACME_CA:-}"
	if [ "$acme_ca" = "staging" ]; then
		acme_ca="https://acme-staging-v02.api.letsencrypt.org/directory"
	fi
	if [ -n "${MPD_ACME_EMAIL:-}" ]; then
		CADDY_GLOBAL_OPTIONS="email ${MPD_ACME_EMAIL}"
	fi
	if [ -n "$acme_ca" ]; then
		CADDY_GLOBAL_OPTIONS="${CADDY_GLOBAL_OPTIONS}
	acme_ca ${acme_ca}"
	fi
	mode_text="Let's Encrypt certificate for $domain${acme_ca:+ (from $acme_ca)}"
	if [ -z "${MPD_PUBLIC_BASE_URL:-}" ]; then
		export MPD_PUBLIC_BASE_URL="https://${domain%% *}"
	fi
else
	hostnames="${MPD_LOCAL_HOSTNAMES:-localhost}"
	CADDY_SITE_ADDRESS="$(echo "$hostnames" | xargs -n1 | sed 's#^#https://#' | paste -sd, - | sed 's/,/, /g')"
	CADDY_TLS="tls internal"
	# A browser opening https://<IP address> sends no server name, and Caddy
	# only sees the container's own address - so tell it which certificate
	# to use then (the first IP address listed).
	first_ip="$(echo "$hostnames" | xargs -n1 | grep -E '^[0-9.]+$|:' | head -1 || true)"
	if [ -n "$first_ip" ]; then
		CADDY_GLOBAL_OPTIONS="default_sni ${first_ip}"
	fi
	mode_text="local certificate for $hostnames"
fi
export CADDY_GLOBAL_OPTIONS CADDY_SITE_ADDRESS CADDY_TLS

echo "myphotodiary ${MPD_VERSION:-}: ${mode_text}"

# Upload temporary files go to /data/tmp, on the data disk, not into the
# container's own (system disk) layer.
# shellcheck disable=SC2086
java ${JAVA_OPTS:-} -Djava.io.tmpdir=/data/tmp -jar /opt/myphotodiary/app.jar \
	--server.address=127.0.0.1 --server.port=8081 &
java_pid=$!

caddy run --config /etc/caddy/Caddyfile --adapter caddyfile &
caddy_pid=$!

stop_both() {
	kill -TERM "$java_pid" "$caddy_pid" 2>/dev/null || true
}
trap stop_both TERM INT

status=0
wait -n "$java_pid" "$caddy_pid" || status=$?
echo "myphotodiary: a process exited (status $status) - stopping the container" >&2
stop_both
wait || true
exit "$status"
