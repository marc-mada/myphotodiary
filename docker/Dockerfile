# myPhotoDiary - single image: backend (Spring Boot + embedded HSQLDB),
# frontend (static files) and Caddy (web server, automatic HTTPS).
#
# Built from the repository root, after the jar and the frontend have been
# built by Maven (docker/build.sh does both):
#   docker build -f docker/Dockerfile --build-arg VERSION=2.10.2 -t myphotodiary:2.10.2 .
#
# Everything installation-specific (domain, secrets, data folders) is given
# when the container starts, never here (see entrypoint.sh for the HTTPS
# settings).

ARG CADDY_VERSION=2.11.7

FROM caddy:${CADDY_VERSION} AS caddy

FROM eclipse-temurin:21-jre-noble

ARG VERSION

# ffmpeg/ffprobe: video validation and thumbnails. tini: a minimal init,
# so signals reach the start script and zombies are reaped. rsync: the
# photo backup/restore scripts below. libcap2-bin: only for setcap below,
# removed again in the same layer.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends ffmpeg tini rsync libcap2-bin \
	&& rm -rf /var/lib/apt/lists/*

# Only Caddy's single program file is taken from its official image.
# setcap lets it listen on ports 80/443 without running as root (Docker
# usually allows that anyway, but not with --network host).
COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
RUN setcap cap_net_bind_service=+ep /usr/bin/caddy \
	&& apt-get purge -y libcap2-bin && apt-get autoremove -y

# An ordinary user for both programs. The UID is what host folders mounted
# under /data must be writable by (or pass --user to use another one).
RUN groupadd --system --gid 10001 myphotodiary \
	&& useradd --system --uid 10001 --gid 10001 --home-dir /data --no-create-home --shell /usr/sbin/nologin myphotodiary \
	&& mkdir -p /data && chown myphotodiary:myphotodiary /data

COPY backend/target/myphotodiary-backend-${VERSION}.jar /opt/myphotodiary/app.jar
COPY frontend/dist/ /opt/myphotodiary/frontend/
COPY docker/Caddyfile /etc/caddy/Caddyfile
# Make everything shipped readable by the ordinary user, whatever the
# permissions were on the build machine (some icons were owner-only, and
# Caddy then answered 403 for them).
RUN chmod -R a+rX /opt/myphotodiary /etc/caddy
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/myphotodiary-entrypoint
# The same backup/restore scripts as the .deb package, run inside the
# container with its own settings:
#   docker exec <container> myphotodiary-backup-images      (daily, from the host's scheduler)
#   docker run --rm -v ...:/data --entrypoint myphotodiary-restore-db <image> ...   (container stopped)
COPY --chmod=755 backend/src/deb/scripts/backup-images.sh /usr/local/bin/myphotodiary-backup-images
COPY --chmod=755 backend/src/deb/scripts/restore-db.sh /usr/local/bin/myphotodiary-restore-db
COPY --chmod=755 backend/src/deb/scripts/restore-images.sh /usr/local/bin/myphotodiary-restore-images

# Fixed paths inside the container; the host side is chosen with -v.
# SERVER_FORWARD_HEADERS_STRATEGY: the backend only listens inside the
# container, behind Caddy, so it can trust Caddy's X-Forwarded-* headers -
# that's how it knows a visitor came in over HTTPS (share-link previews
# need the real https:// address).
ENV MPD_DB_PATH=/data/db/myphotodiary \
	MPD_STORAGE_ROOT=/data/images \
	MPD_STAGING_ROOT=/data/staging \
	MPD_BACKUP_ROOT=/data/backup \
	MPD_BACKUP_REQUIRE_MOUNT=false \
	MPD_BACKUP_MARKER_FILE=.myphotodiary-backup-target \
	MPD_SECRETS_FILE=/data/config/secrets.properties \
	XDG_DATA_HOME=/data/caddy \
	XDG_CONFIG_HOME=/data/caddy \
	JAVA_OPTS="-XX:MaxRAMPercentage=70" \
	SERVER_FORWARD_HEADERS_STRATEGY=framework \
	MPD_VERSION=${VERSION}

LABEL org.opencontainers.image.title="myPhotoDiary" \
	org.opencontainers.image.version="${VERSION}" \
	org.opencontainers.image.source="https://github.com/marc-mada/myphotodiary" \
	org.opencontainers.image.licenses="Apache-2.0"

USER myphotodiary
WORKDIR /data
EXPOSE 80 443 443/udp

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/myphotodiary-entrypoint"]
