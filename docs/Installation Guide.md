# myPhotoDiary — Installation Guide

> Written for myPhotoDiary **2.10**.

Audience: someone installing and administering their own myPhotoDiary
instance (self-hosting), not a developer working on its source code. For
using the application once it's running, see the [User's Manual](User's%20Manual.md).

## Table of Contents

1. [Introduction](#1-introduction)
2. [System Requirements](#2-system-requirements)
3. [Choosing an Installation Method](#3-choosing-an-installation-method)
4. [Web Server and HTTPS (Caddy)](#4-web-server-and-https-caddy)
5. [Debian Package Installation](#5-debian-package-installation)
6. [Docker Installation](#6-docker-installation)
7. [First Run](#7-first-run)
8. [Upgrading](#8-upgrading)
9. [Backup and Restore](#9-backup-and-restore)
10. [Uninstalling](#10-uninstalling)
11. [Troubleshooting](#11-troubleshooting)
12. [Reference: Configuration Settings](#12-reference-configuration-settings)

---

## 1. Introduction

myPhotoDiary is a self-hosted photo and video diary for a family or a
small group: publish, browse, share, tag, rate, comment on, search, and geolocate
a collection of pictures and short videos, organized as a directory tree
(`year/month/sequence`). To provide the best user experience, it has separate
desktop and mobile interfaces,
an admin screen for managing users/groups/roles, and lets you generate
time-limited external links to share photos or videos with someone who
doesn't have an account.

An instance is made of two parts, installed and upgraded independently:

- **Backend** — a Spring Boot application. It serves the REST API, holds
  the database, and stores your pictures/videos on disk.
- **Frontend** — a static build of the React web application. It's just
  files, served by a web server.

A web server, Caddy, ties the two together for anyone visiting the site:
it serves the frontend's static files, forwards API requests to the
backend, and takes care of the HTTPS certificate. §4 covers setting that
up.

Alternatively, a single **Docker image** contains both parts together
with their own web server, which also obtains the HTTPS certificate by
itself — see §6.

This guide is about getting an instance installed, configured, and
running. Once it's up, hand your users the [User's Manual](User's%20Manual.md)
instead — it covers actually using the application.

## 2. System Requirements

- **Operating system**: Debian or Ubuntu Linux for the `.deb`
  packages (§5). For the Docker image (§6), any machine that runs Docker
  — see §6.1.
- **Java**: version 21 or newer, to run the backend. You don't need to
  install this yourself — the backend package depends on
  `openjdk-21-jre-headless` and `apt` installs it automatically (the
  Docker image includes it).
- **`ffmpeg`**: required for video upload support (extracting a preview
  thumbnail and validating the video's codec — no video is ever
  transcoded). Also installed automatically as a package dependency, and
  included in the Docker image.
- **A web server**: Caddy, to serve the frontend and expose the backend
  over HTTPS (§4). Not needed with the Docker image, which has its own.
- **A hostname** pointing to the server, and ports 80/443 reachable from
  the Internet, if the instance will be reachable from the Internet — Caddy
  then gets the HTTPS certificate from
  [Let's Encrypt](https://letsencrypt.org/) by itself.
- **Disk space**: proportional to the size of the photo/video library
  you intend to store. Besides the originals, the backend generates and
  keeps a thumbnail and a medium ("web") resolution derivative for every
  picture, and a thumbnail for every video — budget noticeably more than
  the raw size of your originals alone.
- **On users' own devices**: any modern browser. A couple of desktop-only
  features (e.g. choosing a download folder for original pictures from
  the Search screen) need a Chromium-based browser (Chrome/Edge) and a
  secure context (HTTPS, or `localhost`) — see the User's Manual.
- **Only if you're building the packages yourself from source** (§5.1),
  not needed on the server: a JDK 21 + Maven, and Node.js/`npm`, to
  compile the backend and build the frontend.

## 3. Choosing an Installation Method

There are three ways to get myPhotoDiary running on a server:

| Method | Best for | In this guide |
|---|---|---|
| Debian package (`.deb`) | Debian/Ubuntu servers — clean upgrades and uninstalls via `apt`/`dpkg`, with Caddy as the web server | §4 and §5 |
| Docker image | Any machine running Docker (Linux, a NAS, a Mac) — one container with everything, including automatic HTTPS | §6 |
| Native (manual, systemd service) | Full manual control over every file and path | Not covered |

Both documented methods run the same application with the same data
layout; the later chapters (first run, upgrading, backups, uninstalling)
cover both. If another web server already uses ports 80/443 on the
machine, the Docker image can sit behind it (§6.5).

## 4. Web Server and HTTPS (Caddy)

The frontend package doesn't run a server of its own — it installs static
files to `/opt/myphotodiary/frontend-dist` and expects a web server to
serve them and forward API requests to the backend. This guide uses
**Caddy**: a single program that does both, and obtains and renews the
site's HTTPS certificate from Let's Encrypt by itself — no separate
certificate tool, no renewal to schedule.

### 4.1 Install Caddy

From Caddy's own package repository, which carries the current version
(Ubuntu's own `caddy` package works too, but stays at an older version
for the life of each Ubuntu release):

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install caddy
```

Caddy starts right away with a default page on port 80; §4.2 replaces
it.

**Automatic updates.** Ubuntu's automatic security updates
(`unattended-upgrades`) only cover Ubuntu's own repositories. To have
Caddy updated the same way, add this line to the
`Unattended-Upgrade::Allowed-Origins` list in
`/etc/apt/apt.conf.d/50unattended-upgrades`:

```
        "cloudsmith/caddy/stable:any-version";
```

and check with `sudo unattended-upgrade --dry-run --debug` that `caddy`
is now among the allowed packages. Otherwise, update it now and then with
`sudo apt upgrade`. Each update restarts Caddy (a second or so).

### 4.2 Create the site

Replace `myphotodiary.example.com` with your real hostname, and
`you@example.com` with your email (Let's Encrypt sends certificate
expiry notices there; you can also delete that line):

```bash
sudo tee /etc/caddy/Caddyfile > /dev/null <<'CADDY'
{
	email you@example.com
}

myphotodiary.example.com {
	encode zstd gzip

	# Messaging apps' link-preview robots never run JavaScript, so only
	# the backend can give them a share link's real photo (og:image).
	@linkPreview {
		header_regexp User-Agent (?i)(WhatsApp|facebookexternalhit|Twitterbot|TelegramBot|Slackbot|LinkedInBot|Discordbot|SkypeUriPreview)
		path_regexp ^/share/(image|sequence|search)/
	}
	handle @linkPreview {
		reverse_proxy 127.0.0.1:8090
	}

	handle /api/* {
		reverse_proxy 127.0.0.1:8090
	}

	# The web application. Share links (/share/...) are its only page
	# without a file of its own: they get index.html.
	handle {
		root * /opt/myphotodiary/frontend-dist
		@shareLink path /share/*
		rewrite @shareLink /index.html
		file_server
	}

	log
}
CADDY

sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile   # "Valid configuration"
sudo systemctl reload caddy
```

Nothing else is needed: Caddy passes the original host and protocol on to
the backend, supports video seeking (partial "Range" requests), and has
no upload-size or time limit that would cut off a large video. The
frontend files are already in place, installed by the
`myphotodiary-frontend` package (§5.2).

### 4.3 HTTPS

Caddy gets the certificate on its own, as soon as the site is loaded,
provided:

- your hostname's DNS points to this server's public address, and
- ports 80 and 443 are reachable from the Internet (on a home
  connection: forward both on your router to this server).

Watch it happen, then check from a browser:

```bash
sudo journalctl -u caddy -f     # expect "certificate obtained successfully" within a minute
```

Plain HTTP is redirected to HTTPS automatically, and the certificate is
renewed automatically well before it expires. Caddy keeps it in
`/var/lib/caddy`.

### 4.4 Firewall

Only the ports Caddy listens on need to be reachable from the public
Internet:

| Port | Exposure | What's on it |
|---|---|---|
| 80/tcp | Public | Caddy — plain HTTP, redirected to 443; also used by Let's Encrypt to verify the hostname |
| 443/tcp | Public | Caddy — HTTPS. Serves the frontend's static files and forwards `/api/*` to the backend (§4.2) |
| 443/udp | Public (optional) | Caddy — HTTP/3. Browsers fall back to 443/tcp without it |
| 8090/tcp (backend) | Loopback only | The backend is never reached directly — Caddy reaches it at `127.0.0.1:8090` |

**Example: `ufw`.**

```bash
sudo apt install ufw   # if not already installed
sudo ufw allow OpenSSH        # or your own SSH port - do this *before* enabling ufw, or you can lock yourself out
sudo ufw allow 80,443/tcp
sudo ufw allow 443/udp        # optional, HTTP/3
sudo ufw enable
sudo ufw status verbose
```

Nothing here allows 8090 — leave it that way; `ufw`'s own default is to
deny anything not explicitly allowed, which is what keeps the backend
unreachable from outside.

If your server uses a different firewall mechanism instead (`sudo nft
list ruleset`, `sudo iptables -L -n`, or an upstream/hosting-provider
firewall), open 80 and 443 the equivalent way there, and check the same
thing: port 8090 genuinely isn't reachable from anywhere but
`127.0.0.1`.

## 5. Debian Package Installation

### 5.1 Building or Obtaining the Packages

**Download them (recommended).** Each version is published as a GitHub
Release with both packages attached, together with its release notes and
SHA-256 checksums: <https://github.com/marc-mada/myphotodiary/releases>

On the server, for example for version 2.10.2:

```bash
V=2.10.2
wget https://github.com/marc-mada/myphotodiary/releases/download/v$V/myphotodiary-backend_$V-1_all.deb
wget https://github.com/marc-mada/myphotodiary/releases/download/v$V/myphotodiary-frontend_$V-1_all.deb
sha256sum myphotodiary-*.deb   # compare with the checksums on the release page
```

**Or build them yourself**, on a machine with JDK 21, Maven, and
Node.js/`npm` installed (this does **not** need to be the target
server). Clone the source repository:

```bash
git clone https://github.com/marc-mada/myphotodiary.git
cd myphotodiary
git checkout v2.10.2   # or whichever version you want
mvn -P deb clean package -DskipTests
```

This produces the same two packages:

```
backend/target/myphotodiary-backend_<version>-1_all.deb
frontend/target/myphotodiary-frontend_<version>-1_all.deb
```

Copy both to the target server before continuing.

Without `-P deb`, `mvn clean package` produces the bare build output
instead — the backend as a single executable jar
(`backend/target/myphotodiary-backend-<version>.jar`; ignore the
`.jar.original` file next to it) and the web application as static files
(`frontend/dist/`, always as a whole: file names change at every build).
That's what development uses; to install, use the packages or the Docker
image (§6).

### 5.2 Installing

Run a dry-run first — it resolves each package's declared dependencies
against what's already on the server, without changing anything, and
surfaces a missing dependency or conflict up front:

```bash
sudo apt-get install --dry-run ./myphotodiary-backend_<version>-1_all.deb
sudo apt-get install --dry-run ./myphotodiary-frontend_<version>-1_all.deb
```

Then install both (order doesn't matter between them):

```bash
sudo dpkg -i myphotodiary-backend_<version>-1_all.deb
sudo dpkg -i myphotodiary-frontend_<version>-1_all.deb
sudo apt-get install -f   # only if dpkg reported missing dependencies above
```

Installing the backend package automatically:
- creates a dedicated `myphotodiary` system user and group;
- creates `/var/lib/myphotodiary-backend/{db,images,staging}`, owned by
  that user;
- installs the systemd service, **but deliberately does not start it
  yet** — see §5.3, it needs two secrets set first.

Installing the frontend package installs its static files straight to
`/opt/myphotodiary/frontend-dist`, owned by `www-data` and readable by
the web server; nothing needs reloading when they change.

### 5.3 Post-Install Configuration

The backend package installs its service without starting it (§5.2) so
you can set its configuration first. Edit its environment file:

```bash
sudo nano /etc/myphotodiary-backend/myphotodiary-backend.env
```

Generate each of the two required secrets separately — never reuse the
same value for both, so that leaking or rotating one never affects the
other:

```bash
openssl rand -hex 32   # paste into MPD_VIDEO_TOKEN_SECRET
openssl rand -hex 32   # paste into MPD_SHARE_TOKEN_SECRET
```

These are the variables `myphotodiary-backend.env` contains:

| Variable | Default | Meaning |
|---|---|---|
| `MPD_DB_PATH` | `/var/lib/myphotodiary-backend/db/photoindex` | HSQLDB database file path prefix (no `.script` extension) |
| `MPD_STORAGE_ROOT` | `/var/lib/myphotodiary-backend/images` | Root folder for original pictures/videos, thumbnails, and the medium ("web") resolution derivatives |
| `MPD_STAGING_ROOT` | `/var/lib/myphotodiary-backend/staging` | Root folder for the staging-import feature ("Batch Publish" in Admin → Index management) — where not-yet-organized photos get dropped or mounted before import |
| `MPD_VIDEO_TOKEN_SECRET` | *(empty — required)* | Signs the short-lived tokens used to authorize video streaming. A stable value here means a service restart doesn't invalidate a video that's mid-playback |
| `MPD_SHARE_TOKEN_SECRET` | *(empty — required)* | Signs the longer-lived (30-day) external share links for a single picture or a whole sequence. A stable value here means a service restart doesn't invalidate a link someone may still be using |
| `MPD_PUBLIC_BASE_URL` | *(empty — required behind the web server)* | The absolute `scheme://host` this server is publicly reachable at, e.g. `https://myphotodiary.example.com` (no trailing slash) — the same hostname from §4.2's vhost. Used to build the `og:image` tag on a share link's own preview page (what a messaging app shows before the link is opened) — unlike every other URL this app builds, that one has to be generated on the *server* side, where there's normally no reliable way to know the public-facing address behind the web server |
| `MPD_FFMPEG_PATH` | *(commented out — `ffmpeg` resolved from `PATH`)* | Full path to the `ffmpeg` binary, only needed if it isn't already on this service's own `PATH` (rarely the case, since it's a package dependency) |
| `MPD_FFPROBE_PATH` | *(commented out — `ffprobe` resolved from `PATH`)* | Same as above, for `ffprobe` |
| `SERVER_PORT` | `8090` | The loopback port the backend listens on — never meant to be exposed directly; the web server (§4) is what's actually reachable from outside |

`MPD_DB_PATH`, `MPD_STORAGE_ROOT`, and `MPD_STAGING_ROOT` all default to
locations already created under `/var/lib/myphotodiary-backend` with the
right ownership at install time — only change them if you want that data
on a different disk or partition, in which case create the target
directory and `chown` it to the `myphotodiary` user first.
`MPD_VIDEO_TOKEN_SECRET` and `MPD_SHARE_TOKEN_SECRET` are the only two
settings with no safe default — left empty, the service starts fine but
falls back to a random key generated fresh on every restart, silently
invalidating any video mid-playback or any share link still in use each
time it restarts. Set both now so that doesn't happen. Set
`MPD_PUBLIC_BASE_URL` too, to the same hostname used in §4.2 — left
unset, this backend will still start and every other feature works fine,
but a shared link's preview card falls back to reconstructing an
address from the request the web server forwards, which is normally the
*wrong* scheme (`http`, from the web server's own loopback connection to
this backend, not the `https` the public actually uses).

(The same file also holds a handful of backup-related variables, safe to
leave at their defaults for now — see §9, once you're ready to set up a
backup target.)

Once both secrets are set:

```bash
sudo systemctl enable --now myphotodiary-backend
sudo systemctl status myphotodiary-backend   # expect "active (running)"
curl -u admin:admin http://127.0.0.1:8090/api/me   # smoke check
```

The `curl` check above uses the bootstrap admin account created
automatically the first time the service starts — see §7, "First Run",
for what to do next.

## 6. Docker Installation

One container image holds the whole application: the backend, the web
application, and a web server (Caddy) that obtains and renews its own
HTTPS certificate from Let's Encrypt. It replaces §4 and §5 entirely —
no separate web server, no packages, no Java or `ffmpeg` to install on
the machine.
The same image name works on Intel/AMD and on ARM machines (a
Raspberry Pi, a recent NAS, an Apple Silicon Mac); Docker picks the
right version by itself.

### 6.1 Requirements

- **Docker** on a 64-bit machine:
  - **Linux** is the main target. On Ubuntu or Debian,
    `sudo apt install docker.io` is enough.
  - **macOS** works with Docker Desktop or OrbStack (tested).
  - **Windows** with Docker Desktop in its default "Linux containers"
    mode should work too, but hasn't been tested.
- **Memory**: 2 GB for the container.
- **Disk**: about 1 GB for the image, plus room for your photos (§2).
- **For a public site with a trusted certificate**: a domain name
  pointing to the machine's public address, and ports **80 and 443**
  reachable from the Internet. On a home connection, that means
  forwarding both ports on your router to this machine. Without these,
  the container falls back to a local certificate (§6.5).

A Mac or Windows PC has to stay switched on, with Docker running, for
others to reach the site. That's fine for trying myPhotoDiary out, or on
an always-on Mac mini; a Linux machine or NAS is a better home for a
family server.

### 6.2 Getting the image

From version 2.10.0, each release publishes the image to GitHub's
container registry, `ghcr.io/marc-mada/myphotodiary`:

```bash
docker pull ghcr.io/marc-mada/myphotodiary:2.10.2
```

**Or build it yourself** from the source repository (§5.1), on a machine with JDK 21, Maven, Node.js/`npm` and Docker:

```bash
git checkout v2.10.2
docker/build.sh        # produces the image myphotodiary:2.10.2
```

`docker/build.sh --all` builds both Intel (amd64) and ARM (arm64)
versions; `docker/build.sh --push <registry>/<name>` builds both and
publishes them as `<version>` and `latest` (after `docker login` with
write access). `build.sh` runs `mvn clean package` first, so on a
development machine it replaces the jar a locally running backend may be
using — restart that backend afterwards.

Then use `myphotodiary:2.10.2` instead of
`ghcr.io/marc-mada/myphotodiary:2.10.2` in the commands below.

### 6.3 Preparing the data folder

All the data lives in **one folder on the machine**, mounted at `/data`
inside the container, so it survives every upgrade and container
re-creation. Its subfolders are created automatically on first start:

| Inside the data folder | Contents |
|---|---|
| `db/` | The database |
| `images/` | Photos and videos (`year/month/sequence`), with their thumbnails and medium-size copies |
| `staging/` | Batch Publish source folder (Admin → Index management) — can also be a separate mount, read-only is fine (e.g. a camera card) |
| `backup/` | Backup target — should be a separate mount (§9.5) |
| `config/` | Secrets generated on first start (`secrets.properties`) — keep this file private, and keep it: share links depend on it |
| `caddy/` | HTTPS certificates — keep it, or a new certificate is requested at every start |
| `tmp/` | Temporary upload files |

```bash
mkdir -p /srv/myphotodiary
```

**The container must be able to read and write this folder.** Two ways:

- **Recommended: run the container as the user who owns the photos on
  this machine** — typically your own account, the one you also use to
  copy photos into folders directly. That's the `--user "$(id -u):$(id -g)"`
  line in §6.4. Everything the app creates then belongs to that user,
  and photos you drop into a folder yourself can be indexed without any
  permission problem.
- Or keep the image's own built-in user (UID 10001) and give it the
  folder: `sudo chown -R 10001:10001 /srv/myphotodiary` (and leave out
  the `--user` line). Anything you then copy in by hand must be readable
  *and* writable by UID 10001 too, because thumbnails are written next
  to the photos.

**Existing photos**: copy them under `images/`, keeping the
`year/month/sequence` layout, then index them after the first start
(Admin → Index management, "Batch Publish" of the whole tree).

### 6.4 Starting the container

```bash
docker run -d --name myphotodiary --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  -p 80:80 -p 443:443 -p 443:443/udp \
  -v /srv/myphotodiary:/data \
  -e TZ=Europe/Paris \
  -e MPD_DOMAIN=photos.example.com \
  -e MPD_ACME_EMAIL=you@example.com \
  -e MPD_INITIAL_ADMIN_PASSWORD='choose-a-strong-password' \
  --memory 2g --log-opt max-size=10m --log-opt max-file=3 \
  ghcr.io/marc-mada/myphotodiary:2.10.2

docker logs -f myphotodiary     # Ctrl-C to stop following
```

The log should show `myphotodiary 2.10.2: Let's Encrypt certificate for
photos.example.com`, then `certificate obtained successfully` within a
minute or so. Open `https://photos.example.com` and sign in as `admin`
with the password you chose (§7).

- **Try the certificate first with Let's Encrypt's test service**: add
  `-e MPD_ACME_CA=staging`. Browsers will warn about that test
  certificate, but it proves the domain and ports are right without
  using up Let's Encrypt's limits (5 identical certificates a week). Once
  it works, re-create the container without that line.
- **`TZ`**: without it the container runs on UTC. That shifts the time
  of the nightly database backup, and the date given to a photo that has
  no date of its own.
- **`MPD_INITIAL_ADMIN_PASSWORD`** is only used on the very first start,
  to create the `admin` account. Without it, the password is `admin`.
- **Firewall**: ports published with `-p` are opened by Docker itself,
  bypassing `ufw` rules. That's intended for 80 and 443 here; don't
  publish any other port.
- **Prefer a file over a long command?** The repository has the same
  settings as a Docker Compose file: `docker/compose.example.yml`. Copy
  it as `compose.yml`, put your values in a `.env` file next to it (the
  file explains how), and start with `docker compose up -d`.

Everything else needs no setting: the token secrets are generated on
first start and kept in `config/secrets.properties`, and the public
address used for share-link previews is derived from `MPD_DOMAIN`.

### 6.5 HTTPS modes

| Setting | What the container does | When to use it |
|---|---|---|
| `MPD_DOMAIN=photos.example.com` | Gets and renews a Let's Encrypt certificate for that name; redirects HTTP to HTTPS | A public site (§6.1's requirements) |
| *(no `MPD_DOMAIN`)* | Uses a certificate from Caddy's own local authority, for the names in `MPD_LOCAL_HOSTNAMES` (default `localhost`; add the machine's local address or name, separated by spaces) | Trying it out, or a home-network-only instance. Browsers warn until you trust that authority's root certificate, `caddy/caddy/pki/authorities/local/root.crt` in the data folder |
| `MPD_HTTPS=off` | Plain HTTP on port 80 | Only behind another web server that handles HTTPS (below) |

Signing in requires the browser to use HTTPS, so `MPD_HTTPS=off` without
an HTTPS web server in front won't let anyone sign in.

**Behind a web server you already run.** If another web server already
serves other sites on ports 80/443 of the machine, keep it, and let it
forward this site to the container. Start the container with these lines
instead of the ports and `MPD_DOMAIN` of §6.4:

```bash
  -p 127.0.0.1:8088:80 \
  -e MPD_HTTPS=off \
```

and have that web server forward everything for your site's name to
`127.0.0.1:8088` — the container itself handles share links and link
previews, so none of §4.2's routes are needed. With Caddy, the whole site
is:

```
photos.example.com {
	reverse_proxy 127.0.0.1:8088
}
```

Another web server must do the same, and also pass on the original
`Host` header and an `X-Forwarded-Proto: https` header (Caddy does both
by itself): that header is how the app knows visitors use HTTPS — it
needs it to build correct share-link previews. Allow large uploads and
long requests too (a video upload can take minutes).

### 6.6 Container settings

Passed with `-e NAME=value` (or in the Compose `.env` file). Changing one
means re-creating the container (`docker rm -f myphotodiary`, then the
same `docker run` with the new value) — the data folder is unaffected.

| Setting | Default | Meaning |
|---|---|---|
| `MPD_DOMAIN` | *(unset)* | Public name(s) to get a Let's Encrypt certificate for (separate several with spaces) — §6.5 |
| `MPD_ACME_EMAIL` | *(unset)* | Given to Let's Encrypt for certificate expiry notices |
| `MPD_ACME_CA` | *(unset)* | `staging` to use Let's Encrypt's test service while setting up; or another ACME service's directory URL |
| `MPD_LOCAL_HOSTNAMES` | `localhost` | Names/addresses covered by the local certificate when `MPD_DOMAIN` isn't set |
| `MPD_HTTPS` | *(on)* | `off`: plain HTTP behind another HTTPS web server |
| `MPD_INITIAL_ADMIN_PASSWORD` | `admin` | Password of the `admin` account created on the very first start |
| `TZ` | `UTC` | Time zone, e.g. `Europe/Paris` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=70` | Java options; by default Java uses up to 70% of the container's memory limit |

The other backend settings of §12.1 can be passed the same way (for
example `MPD_BACKUP_RETENTION_DAYS`), except the folder paths, which the
image fixes under `/data`. The image also sets `MPD_BACKUP_MARKER_FILE`
and `MPD_SECRETS_FILE` (§12.1) for you.

## 7. First Run

The first time the backend starts against an empty database, it creates
its own schema automatically (no manual step needed) and a bootstrap
administrator account, username `admin`. Its password is the one given in
`MPD_INITIAL_ADMIN_PASSWORD` (§6.4, §12.1) if you set it before that first
start, otherwise `admin`.

1. Open your instance's URL in a browser (the hostname you configured in
   §4 or §6) and sign in as `admin`.
2. If the password is still `admin`, go to **Admin → Users** and change
   it immediately — it's a well-known default, not meant to be kept.
3. From there, see the [User's Manual](User's%20Manual.md) for a guided
   tour: creating other user accounts and assigning them roles, and
   publishing your first pictures.

## 8. Upgrading

**Always back up first.** A new version may include a database schema
change (applied automatically on first startup, via Flyway) that isn't
easily reversible once applied.

### 8.1 Upgrading the Debian packages

Back up the database and the photos:

```bash
sudo tar czf /backup/pre-upgrade-$(date +%F).tar.gz \
  /var/lib/myphotodiary-backend/db /var/lib/myphotodiary-backend/images
```
(Adjust the paths above if you pointed the backend at a different
location in §5.3.)

Build or obtain the new version's two packages (§5.1), then dry-run and
install both, same as a first install:

```bash
sudo apt-get install --dry-run ./myphotodiary-backend_<new-version>-1_all.deb
sudo apt-get install --dry-run ./myphotodiary-frontend_<new-version>-1_all.deb

sudo dpkg -i myphotodiary-backend_<new-version>-1_all.deb
sudo dpkg -i myphotodiary-frontend_<new-version>-1_all.deb
```

What happens automatically:
- The running backend service is stopped before its files are replaced,
  and the jar and systemd unit are updated in place.
- Your edited `/etc/myphotodiary-backend/myphotodiary-backend.env`
  survives untouched (it's a protected configuration file, not
  overwritten by an upgrade).
- The frontend's static files are updated, and any file that belonged to
  the old version but not the new one is removed automatically — no
  stale files left behind.

**The backend service is *not* automatically restarted after an
upgrade** — start it by hand once the install finishes:

```bash
sudo systemctl start myphotodiary-backend
sudo systemctl status myphotodiary-backend   # confirm "active (running)"
```

Then sign in as an administrator and open **Admin → Configure**: the
line under the App-wide settings box shows the version of the backend
that is actually running. If it still shows the old version, the new
package didn't really replace the running program — check that the
service was restarted, before looking for any other cause.

Finish with a smoke test: sign in, browse a sequence, upload a picture,
run a search, and play back a video (seek partway through it, not just
confirm it starts). If something is wrong, reinstall the previous
version's packages — keep them on hand until you've confirmed the
upgrade went well. The database schema migration is the one part of an
upgrade that's genuinely hard to undo, which is exactly what the backup
above is for. (Once the backup system below is set up, a fresh Admin →
Backup → "Back up now" click covers the database half of this same
precaution automatically — the `tar` command above still works fine on
its own for a quick one-off, either way.)

### 8.2 Upgrading with Docker

The data folder is outside the container, so upgrading means replacing
the container with one from the new image — same command, new version
number:

```bash
# Back up first: Admin -> Backup -> "Back up now" (§9), or copy the data folder's db/ while the container is stopped.
docker pull ghcr.io/marc-mada/myphotodiary:<new-version>
docker rm -f myphotodiary
docker run -d --name myphotodiary ...same options as in §6.4... ghcr.io/marc-mada/myphotodiary:<new-version>
docker logs -f myphotodiary    # a database migration, if any, is applied here
```

With Compose: change the version in `compose.yml`, then
`docker compose pull && docker compose up -d`. Photos, database, secrets
and certificates are all kept. Check the version under Admin → Configure,
as in §8.1, and finish with the same smoke test. To go back, start the previous image version again — after a
database migration, that also needs the database backup. Old image
versions stay on disk until you remove them (`docker image prune -a`).

## 9. Backup and Restore

Two independent halves, both optional and both safe to leave switched on
with no target configured — each one's own guard refuses to write
anything until a real backup destination is set up (below), reporting
"not mounted" rather than silently protecting nothing:

- **Database** — a nightly snapshot, taken in-process by the backend
  itself, plus an Admin-triggered "Back up now" button. See the User's
  Manual's Admin section for using it day to day.
- **Original photos and videos** — a separate daily snapshot
  (`rsync --link-dest`, hardlinked against the previous one so only
  genuinely new or changed files cost extra disk space) run by its own
  systemd timer, independent of whether the backend itself is up.
  Thumbnails and the medium-resolution derivatives are deliberately
  **not** backed up — they're regenerated automatically by re-indexing
  (Admin → Index management), so backing them up separately would only
  cost real disk/network space for zero actual protection.

### 9.1 Choosing and preparing a backup target

Any network share or second disk works identically here, as long as it
ends up mounted as its own genuine filesystem at a path of your choosing
— this app only ever checks *that*, never what kind of storage backs it.
A NAS over NFS is the most common choice; the steps below use that as the
concrete example.

**On the NAS**: create a dedicated shared folder for this purpose, sized
generously (both halves' own automatic pruning, §9.3, keeps the ongoing
cost proportional to what actually changes, but the very first backup
copies your whole library once, in full). Export it over NFS to this
server's own address only — NFS access control is IP-based, not
username/password — with read/write access and **no UID/GID mapping**
("no mapping"/"no squash" in most NAS admin UIs), so the backend's own
service account can write to it using its real identity. **Prefer
NFSv3** over NFSv4 if your NAS offers a choice: NFSv4's own separate
identity-mapping layer (matching a client-side and NAS-side "domain")
can silently reject writes from an account that otherwise has every
permission it needs, in a way that's easy to misdiagnose; NFSv3 sends
plain numeric user/group IDs instead, with nothing extra to configure or
mismatch.

**On this server**:

```bash
sudo apt install nfs-common   # if not already present
sudo mkdir -p /mnt/myphotodiary-backup
```

Add to `/etc/fstab` (adjust the address/export path to your own NAS —
`vers=3` for the reason above):
```
<nas-address>:/path/to/share  /mnt/myphotodiary-backup  nfs  _netdev,hard,timeo=50,retrans=3,vers=3  0  0
```
`hard`, not `soft` — a `soft` mount can silently return a short read/write
on a NAS timeout instead of a clear error, exactly the kind of corruption
risk a backup target can least afford. `hard` instead simply blocks until
the NAS answers (interruptible, not an indefinite hang).

```bash
sudo mount -a
mountpoint -q /mnt/myphotodiary-backup && echo "mounted OK"
```

**The share also needs to be genuinely writable by the `myphotodiary`
service account**, not just reachable — a freshly created NAS shared
folder is commonly owned by an administrative account with restrictive
permissions by default, which silently blocks every backup run
regardless of the NFS export settings above. Confirm and fix if needed:

```bash
sudo -u myphotodiary touch /mnt/myphotodiary-backup/perm-test && \
  sudo -u myphotodiary rm /mnt/myphotodiary-backup/perm-test && \
  echo "writable OK"
```
If that fails, fix the share's ownership/permissions from the NAS's own
administration interface (or, for an NFS share with root access enabled,
directly from this server: `sudo chown <myphotodiary-uid>:<myphotodiary-gid>
/mnt/myphotodiary-backup`, using the real IDs from `id myphotodiary`).

**Synology (DSM) notes** — four settings that each caused a real failure:
- DSM enables NFSv4.1 by default. Enable **NFSv3** explicitly as well
  (Control Panel → File Services → NFS), or the `vers=3` mount fails.
- In the shared folder's NFS permission rule, check **"Allow connections
  from non-privileged ports"** (Linux clients need it), choose
  Squash **"No mapping"**, and leave "Enable asynchronous" off.
- After changing `/etc/fstab`, check what is *actually* mounted with
  `mount | grep myphotodiary-backup`: `mount -a` skips a mount point
  that is already mounted, so run `sudo umount /mnt/myphotodiary-backup`
  first.
- If the folder's permissions reset on their own to `d---------`, the
  shared folder uses DSM's "Windows ACL" permissions, which can override
  a `chmod` made over NFS (root is not affected, which hides the
  problem). Fix it in DSM: File Station → the shared folder → Properties
  → Permission, and give the `myphotodiary` account (or Everyone)
  read/write access. Then repeat the `perm-test` check above.

**Before trusting the target, confirm hardlinks actually work over it** —
the image-tree backup's own space-saving mechanism depends on them, and
some NAS/NFS combinations are known to reject hardlink creation even
though the underlying filesystem supports it locally:
```bash
touch /mnt/myphotodiary-backup/test-a
ln /mnt/myphotodiary-backup/test-a /mnt/myphotodiary-backup/test-b
ls -lai /mnt/myphotodiary-backup/   # both entries must show the SAME
                                     # inode number (first column) and a
                                     # link count of 2
rm /mnt/myphotodiary-backup/test-a /mnt/myphotodiary-backup/test-b
```

### 9.2 Turning it on

Point the backend at the mounted target:

```bash
sudo nano /etc/myphotodiary-backend/myphotodiary-backend.env
```
```
MPD_BACKUP_ROOT=/mnt/myphotodiary-backup
```
```bash
sudo systemctl restart myphotodiary-backend
sudo systemctl restart myphotodiary-backend-backup.timer
```

| Variable | Default | Meaning |
|---|---|---|
| `MPD_BACKUP_ROOT` | *(unset)* | The mounted backup target from §9.1. Both halves refuse to write anything at all until this is set — see the intro above |
| `MPD_BACKUP_ENABLED` | `true` | Set to `false` to disable the in-process database backup entirely (e.g. to rely on an external mechanism instead) |
| `MPD_BACKUP_REQUIRE_MOUNT` | `true` | Refuses to write unless the target is a genuine separate mount, not just an ordinary local folder at that path. Only turn off once you've made a deliberate decision to accept a same-disk "backup" — see the comment in `myphotodiary-backend.env.example` for why that protects against much less |
| `MPD_BACKUP_RETENTION_DAYS` | `30` | How many days of daily snapshots to keep, both halves — older ones are pruned automatically after each new successful one |
| `MPD_DB_BACKUP_CRON` | `0 0 2 * * *` | Spring cron expression (6 fields) for the nightly database snapshot's own schedule |

The image-tree half runs on its own daily systemd timer
(`myphotodiary-backend-backup.timer`, fixed at 03:00, independent of the
cron expression above) — already enabled by the package install (§5.2),
safe to leave that way even before a target is configured.

Verify: Admin → Backup should show the target as mounted/ready rather
than the "not mounted" warning; a "Back up now" click there should
produce a new snapshot in the table. To confirm the image-tree half
without waiting for its own 03:00 schedule:
```bash
sudo systemctl start myphotodiary-backend-backup.service
sudo systemctl status myphotodiary-backend-backup.service   # exit code 0 = success
```

### 9.3 Retention

Both halves prune snapshots older than `MPD_BACKUP_RETENTION_DAYS`
(default 30) automatically, after each successful new one — nothing to
schedule separately.

### 9.4 Restoring

Deliberately a manual, supervised procedure — never a button, never
automated, on purpose (the same reasoning applies as for every other
irreversible admin action in this application: a mistaken restore should
never be one accidental click away). Both scripts default to a dry-run
(nothing written) unless `--yes` is given.

```bash
# Database - see what's available first:
ls /mnt/myphotodiary-backup/db/

sudo systemctl stop myphotodiary-backend   # required - the script refuses otherwise
sudo /usr/share/myphotodiary-backend/scripts/restore-db.sh \
  /mnt/myphotodiary-backup /var/lib/myphotodiary-backend/db <timestamp>          # dry-run
sudo /usr/share/myphotodiary-backend/scripts/restore-db.sh \
  /mnt/myphotodiary-backup /var/lib/myphotodiary-backend/db <timestamp> --yes    # actually restores
sudo systemctl start myphotodiary-backend
```
The script keeps a timestamped copy of whatever live files it just
overwrote (`<db-dir>/.pre-restore-<timestamp>/`) — a safety net if the
chosen snapshot turns out to be the wrong one. Delete it by hand once
you're confident the restore was correct, not before.

```bash
# Photos/videos:
ls /mnt/myphotodiary-backup/images/

/usr/share/myphotodiary-backend/scripts/restore-images.sh \
  /mnt/myphotodiary-backup /var/lib/myphotodiary-backend/images <timestamp>          # dry-run
/usr/share/myphotodiary-backend/scripts/restore-images.sh \
  /mnt/myphotodiary-backup /var/lib/myphotodiary-backend/images <timestamp> --yes    # merges in - never
                                                                                       # deletes anything not
                                                                                       # in the snapshot unless
                                                                                       # --exact is also given
```
Afterward, re-index the affected directories (Admin → Index management,
or Batch Publish) — only originals are ever backed up, so thumbnails and
the medium-resolution derivatives need regenerating.

**Testing a restore without touching production.** Both scripts can be
rehearsed safely, without real downtime or risk:

- **Images** — point `restore-images.sh` at a scratch directory instead
  of the live one; it's non-destructive by default (merges, never
  deletes) and needs no service stopped either.
- **Database** — `restore-db.sh` itself refuses to run at all while the
  backend is active, so instead of fighting that guard for a harmless
  check, copy a chosen snapshot by hand into a scratch directory and
  start a second, throwaway instance of the same jar pointed at it, on a
  different port (the live database is a separate file, so there's no
  conflict either way):
  ```bash
  mkdir -p /tmp/restore-test/db
  cp -a /mnt/myphotodiary-backup/db/<timestamp>/. /tmp/restore-test/db/

  sudo -u myphotodiary sh -c '
    set -a; . /etc/myphotodiary-backend/myphotodiary-backend.env; set +a
    export MPD_DB_PATH=/tmp/restore-test/db/photoindex
    export SERVER_PORT=8091
    export MPD_BACKUP_ENABLED=false
    java -jar /usr/share/myphotodiary-backend/myphotodiary-backend.jar
  '
  ```
  A clean startup log (Flyway's own "Successfully validated N migrations",
  then "Started CmsApplication", no errors) confirms the snapshot is
  structurally intact and openable. Ctrl-C to stop it, `rm -rf
  /tmp/restore-test` to clean up — production is never touched.

### 9.5 With Docker

The same two halves work in the container, with three differences:

- **Mount the backup target into the container** at `/data/backup`,
  after mounting it on the machine itself (§9.1): add
  `-v /mnt/myphotodiary-backup:/data/backup` to the `docker run` command
  of §6.4 (or uncomment the line in the Compose file).
- **Create the marker file once on the real backup target**, while it's
  mounted:
  ```bash
  touch /mnt/myphotodiary-backup/.myphotodiary-backup-target
  ```
  Inside a container, every mounted folder looks like a separate disk,
  so the "is it really mounted?" check of §9 can't tell a NAS from an
  empty local folder. The image therefore checks for this file instead:
  until it's there, both halves refuse to write anything and Admin →
  Backup says the marker file is missing. If the NAS isn't mounted one
  day, the file isn't visible either, and nothing gets written onto the
  wrong disk.
- **The photo backup is run by the machine's own scheduler**, through
  the container — for example, a nightly line in `crontab -e`:
  ```
  0 3 * * * docker exec myphotodiary myphotodiary-backup-images
  ```
  The database half needs nothing: it runs inside the container every
  night (`MPD_DB_BACKUP_CRON`, in the container's time zone — set `TZ`).

**Restoring** works with the same scripts, run in a temporary container
while the real one is stopped:

```bash
docker stop myphotodiary
ls /mnt/myphotodiary-backup/db/        # pick a snapshot
docker run --rm --user "$(id -u):$(id -g)" \
  -v /srv/myphotodiary:/data -v /mnt/myphotodiary-backup:/data/backup \
  --entrypoint myphotodiary-restore-db ghcr.io/marc-mada/myphotodiary:2.10.2 \
  /data/backup /data/db <timestamp>            # dry-run; add --yes to restore
docker run --rm --user "$(id -u):$(id -g)" \
  -v /srv/myphotodiary:/data -v /mnt/myphotodiary-backup:/data/backup \
  --entrypoint myphotodiary-restore-images ghcr.io/marc-mada/myphotodiary:2.10.2 \
  /data/backup /data/images <timestamp>        # dry-run; add --yes to restore
docker start myphotodiary
```

As in §9.4, re-index the restored directories afterwards.

## 10. Uninstalling

```bash
sudo dpkg -r myphotodiary-backend    # stops the service; keeps configuration and data
sudo dpkg -P myphotodiary-backend    # purge - removes the configuration file too
sudo dpkg -r myphotodiary-frontend   # static files only, nothing else to clean up
```

**Your real data — the database and every picture/video — is never
deleted automatically, by either command, even by a purge.** This is
deliberate: an irreversible `apt purge` silently deleting a family's
photo collection would be a far worse outcome than leaving disk space
behind to reclaim later. If you're certain you want it gone:

```bash
sudo rm -rf /var/lib/myphotodiary-backend
```
(Or wherever you pointed the backend's data at in §5.3.)

Uninstalling doesn't touch Caddy or the site created in §4 either. If
you're tearing the whole instance down and nothing else on the machine
uses Caddy:

```bash
sudo apt remove caddy
```

**With Docker:**

```bash
docker rm -f myphotodiary                          # the container
docker rmi ghcr.io/marc-mada/myphotodiary:2.10.2   # the image
```

The data folder (§6.3) is left untouched; delete it yourself once you're
certain you don't need it.

## 11. Troubleshooting

Start with the backend's own log — nearly every problem below shows up
there first:

```bash
sudo journalctl -u myphotodiary-backend -n 200 --no-pager
sudo systemctl status -l myphotodiary-backend
```

The problems actually met while running myPhotoDiary so far, and their
fix:

- **The service won't start: "Start request repeated too quickly".** It
  dies within a second of starting, which almost always means an older
  Java is being used. The package runs `/usr/bin/java`, which follows
  the system's default Java; if another program changed that default,
  pick Java 21 again with `sudo update-alternatives --config java`, then
  `sudo systemctl restart myphotodiary-backend`. The package also warns
  about this at install time.
- **A feature from the new version seems to do nothing after an
  upgrade**, with no error anywhere. The old program is probably still
  the one running. Check the version shown under Admin → Configure
  (§8.1), and restart the service.
- **Video upload is refused.** Check that `ffmpeg -version` and
  `ffprobe -version` work for the service (installed as package
  dependencies; set `MPD_FFMPEG_PATH`/`MPD_FFPROBE_PATH` if they live
  somewhere unusual). A video is also refused, on purpose, if it's larger
  than the maximum set in Admin → Configure or isn't H.264 (for example
  an iPhone's "High Efficiency" HEVC recording) — the error message says
  which.
- **Shared links end on a "Not Found" page.** The `/share/` route is
  missing from your Caddyfile (§4.2): add it, then
  `sudo systemctl reload caddy`.
- **Shared links work, but messaging apps show a generic icon instead of
  the picture.** Set `MPD_PUBLIC_BASE_URL` (§12.1) to your site's public
  `https://` address, restart the backend, and check that your Caddyfile
  has the `@linkPreview` part of §4.2, including `search` in its
  `image|sequence|search` list. Messaging apps also cache previews for a
  while, so try with a newly created link.
- **No certificate, or the browser warns about it.** Caddy's log says why
  (`sudo journalctl -u caddy`): usually the hostname doesn't point to this
  server yet, or ports 80/443 don't reach it from the Internet (§4.3).
- **Admin → Backup says the backup target isn't mounted.** Nothing is
  written until it is: check the mount (`mountpoint /your/backup/root`)
  and see §9.1. On a Synology or similar NAS, a share that root can
  write to but the service account can't is usually a user-mapping or
  permissions problem on the NAS side — §9.1 covers the settings that
  were needed in practice.
- **Photo capture times look an hour or two late.** Versions before 2.8
  read the camera's time as if it were UTC, adding the server's time
  zone offset. 2.8 fixes this for photos published or re-indexed from
  now on; dates stored before stay as they were. Re-indexing a sequence
  (Admin → Index management) recomputes its photos' dates — but a date
  that came from the browser at upload time, for a photo with no EXIF
  date of its own, is replaced by the file's date on the server, so only
  re-index sequences whose photos carry EXIF dates.

**With Docker**, read the container's log instead (`docker logs
myphotodiary`). Problems specific to the container:

- **The container stops right away with "/data/… is not writable by
  user …".** It can't write to the data folder: run it as the folder's
  owner (`--user`), or give the folder to UID 10001 — §6.3.
- **Indexing photos you copied in yourself fails** with "Failed to
  generate derived images". The container can't read those files, or
  can't create the thumbnail folders next to them — they belong to
  another user. Run the container as the user who copies the photos in,
  or fix their ownership (§6.3).
- **"Database lock acquisition failure" in the log.** Another program is
  already using the same data folder: a second container, or the `.deb`
  service. Only one may run against a given database.
- **No certificate.** The log says why. Check that the name points to
  this machine's public address and that ports 80 and 443 reach it
  (router forwarding). After several failed attempts Let's Encrypt
  makes you wait: switch to `MPD_ACME_CA=staging` until the setup works
  (§6.4).
- **Admin → Backup says the marker file is missing.** Create it on the
  backup target (§9.5), or check that the target is mounted on the
  machine.

## 12. Reference: Configuration Settings

This section collects, in one place, every setting that controls a
myPhotoDiary instance — both the ones you set once at install time (as
environment variables, §5.3) and the ones an ADMIN adjusts afterwards from
within the running application itself.

### 12.1 Environment variables (backend)

With the `.deb` packages they're set in
`/etc/myphotodiary-backend/myphotodiary-backend.env`, read by the systemd
service at startup (§5.3); changing one requires a restart
(`sudo systemctl restart myphotodiary-backend`). With Docker they're
passed to the container instead (§6.6), which fixes the folder paths and
sets the marker file and secrets file itself.

| Variable | Default | Meaning |
|---|---|---|
| `MPD_DB_PATH` | `/var/lib/myphotodiary-backend/db/photoindex` | HSQLDB database file path prefix (no `.script` extension) |
| `MPD_STORAGE_ROOT` | `/var/lib/myphotodiary-backend/images` | Root folder for original pictures/videos, thumbnails, and the medium ("web") resolution derivatives |
| `MPD_STAGING_ROOT` | `/var/lib/myphotodiary-backend/staging` | Root folder for the staging-import feature ("Batch Publish" in Admin → Index management) — where not-yet-organized photos get dropped or mounted before import |
| `MPD_VIDEO_TOKEN_SECRET` | *(empty)* | Signs the short-lived tokens used to authorize video streaming. Left empty, the service still starts, but falls back to a random key generated fresh on every restart — silently invalidating any video mid-playback each time it restarts. Set once and keep it (`openssl rand -hex 32`) |
| `MPD_SHARE_TOKEN_SECRET` | *(empty)* | Signs the longer-lived (30-day) external share links for a single picture or a whole sequence. Same random-per-restart fallback and trade-off as above if left unset — set once and keep it, and never reuse the same value as `MPD_VIDEO_TOKEN_SECRET` |
| `MPD_PUBLIC_BASE_URL` | *(empty)* | The absolute `scheme://host` this server is publicly reachable at (§5.3, §4.2's own hostname). Left unset, a shared link's preview card falls back to reconstructing an address from the request the web server forwards — normally the wrong scheme |
| `MPD_FFMPEG_PATH` | *(unset — `ffmpeg` resolved from `PATH`)* | Full path to the `ffmpeg` binary. Only needed if it isn't already on this service's own `PATH` — rarely the case, since `ffmpeg` is a package dependency (§2) |
| `MPD_FFPROBE_PATH` | *(unset — `ffprobe` resolved from `PATH`)* | Same as above, for `ffprobe` |
| `SERVER_PORT` | `8090` | The loopback port the backend listens on. Not meant to be reachable from outside — the web server (§4) is what's actually exposed; the firewall table in §4.4 assumes this default |
| `MPD_BACKUP_ROOT` | *(unset)* | The mounted backup target (§9.1). Both backup halves refuse to write anything at all until this is set |
| `MPD_BACKUP_ENABLED` | `true` | Set to `false` to disable the in-process database backup entirely |
| `MPD_BACKUP_REQUIRE_MOUNT` | `true` | Refuses to write unless the target is a genuine separate mount, not just an ordinary folder at that path — see §9.1 before turning this off |
| `MPD_BACKUP_RETENTION_DAYS` | `30` | How many days of daily backup snapshots to keep, both halves |
| `MPD_DB_BACKUP_CRON` | `0 0 2 * * *` | Spring cron expression (6 fields) for the nightly database snapshot |
| `MPD_BACKUP_MARKER_FILE` | *(unset; `.myphotodiary-backup-target` in the Docker image)* | When set, both backup halves refuse to write unless a file of this name exists at the backup target — created once on the real target, so an unmounted NAS can't go unnoticed (§9.5). Optional extra safety with the `.deb` packages too |
| `MPD_SECRETS_FILE` | *(unset; `/data/config/secrets.properties` in the Docker image)* | When set, the two token secrets above, if not given, are generated once and kept in this file, so share links survive restarts without hand-made secrets |
| `MPD_INITIAL_ADMIN_PASSWORD` | *(unset — `admin`)* | Password of the `admin` account created on the very first start (empty database only) |

`MPD_VIDEO_TOKEN_SECRET` and `MPD_SHARE_TOKEN_SECRET` are the only two
with no safe default — every other variable can be left as-is for a
first install, and the five backup ones can be left as-is until you're
ready to set up a backup target at all (§9). `MPD_PUBLIC_BASE_URL` is a
different kind of gap than those two: leaving it unset never stops the
service from starting or breaks anything else, it just means shared
links get the wrong link-preview image — worth setting during initial
setup (§5.3) rather than only once someone reports it, but not a reason
to delay getting everything else running first.

### 12.2 Settings changed from the Admin screen (no restart needed)

These are **not** environment variables — they live in the database and
take effect immediately, from within the running application. See the
User's Manual's Admin section for where to find each one.

| Setting | Scope | Where | Default |
|---|---|---|---|
| Maximum video upload size | Whole instance | Admin → Configure, "App-wide settings" | 20 MB |
| Target average upload time per photo — above it, photos are made smaller in the browser before upload on a slow connection (never below 2400 px on the long side) | Whole instance | Same box as above | 10 seconds |
| Maximum pictures in a shared search result — above it, the first ones are shared and the person sharing is warned | Whole instance | Same box as above | 50 |
| Slideshow delay | Per user | Admin → Configure, "User settings" (a WRITER can only edit their own; an ADMIN can pick any user) | 5 seconds |
| Post-it fade delay | Per user | Same panel as above | 8 seconds |
| Default map center (latitude/longitude) | Per user | Same panel as above | 48.8567, 2.3508 (Paris) |
| Search results page size | Per user | No screen yet — stays at its default | 10 |

Nothing in this second table needs a service restart, an environment
variable, or SSH access to the server — an ADMIN manages all of it
through the web interface itself, at any time.
