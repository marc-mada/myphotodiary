<p align="left">
  <img src="docs/images/photodiary-logo.jpg" width="300" alt="myPhotoDiary logo">
</p>

# myPhotoDiary: Your memories shared with the people who matter

We capture countless moments of our lives through photos and videos. Family gatherings, holidays, celebrations, everyday moments… Together, they tell the story of our lives and create memories worth preserving.

Yet, our photos are often scattered across phones, social networks, and cloud services. While these platforms make sharing easy, they don't always offer the privacy, control, or long-term preservation we want for our personal and family memories.

**What if you could create your own private space to share these memories with family and friends?**

This self-hosted photo management platform lets you organize, preserve, and share your personal photos and videos in a space you control. Bring your loved ones together around the moments that matter, without relying on public social networks or entrusting your entire collection to commercial photo platforms.

## Everything you need to manage and share your memories

Designed for families and small groups of friends, this self-hosted web application makes it easy to store, browse, organize, and share collections of photos and short videos.

* **Organized collections** — Browse your media through a familiar directory structure, organized by year, month, and sequence (`year/month/sequence`).
* **Find and rediscover memories** — Photos and videos are automatically indexed, making it easy to search your collection. Add comments, tags, and ratings to enrich and organize your memories.
* **Share privately** — Share individual photos or entire sequences with anyone, even if they don't have an account, using external links that automatically expire after a configurable period.
* **Desktop and mobile access** — Dedicated desktop and mobile interfaces share the same backend, with a responsive experience designed to work smoothly across different devices and network conditions.
* **Manage your community** — An administration interface lets you manage users, groups, and roles, giving you control over who can access and share your collections.

## Your memories, hosted your way

First developed in 2014 using Java Servlets, JSP, and JavaScript, the application is being rebuilt and modernized in 2026 with **Spring Boot and React**.

Deployment is designed to be straightforward. The application is available as a package for Debian and Ubuntu, or as a single Docker image bundling the backend, web application, and web server with automatic HTTPS certificate management.

Run it on your own Linux machine, NAS, or Mac — whether powered by Intel or ARM — and keep your photo collection under your control.

## Protect your memories with automatic backups

The application can automatically back up its data every day, including photos, videos, and the database, to a NAS server.

## Download

Debian packages for the backend and the frontend are attached to each
[GitHub Release](https://github.com/marc-mada/myphotodiary/releases) — see the Installation Guide (§5) for installing them.

From 2.10.0, each release is also published as a Docker image,
`ghcr.io/marc-mada/myphotodiary` — see the Installation Guide (§6).

## Documentation

- [Installation Guide](docs/Installation%20Guide.md) — installing and
  administering your own instance.
- [User's Manual](docs/User's%20Manual.md) — using it day to day, on
  desktop or on a phone/tablet.
- [Design](docs/Design.md) — architecture, design decisions and known
  limitations, for developers.

## Structure

- [`backend/`](backend/) — Spring Boot REST API, HSQLDB, Flyway-managed
  schema.
- [`frontend/`](frontend/) — React + Vite, separate desktop/mobile
  presentation trees sharing one API layer.
- [`docker/`](docker/) — single-image build (backend, frontend and
  Caddy).

The legacy application this project replaces (Java Servlet/JSP, 2014) is
not part of this repository; [Design.md](docs/Design.md) §15–§16
describes it and how it was ported.

## License

Copyright 2014-2026 Marc Lamberton.

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).
Every source file also carries this notice in its header.

myPhotoDiary bundles third-party components under their own licenses —
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Release Notes

### 2.10.3 — 2026-10-09

- Mobile: zoom into a picture with two fingers, also in landscape/
  full-screen. Only the picture is magnified; the corner buttons stay in
  place. Drag with one finger to move around a zoomed picture.
- **Fix** (mobile): on a phone, the corner buttons, menu and banner no
  longer grow when the phone is turned to landscape (only tablets get the
  larger size), and on a tablet they no longer shrink in landscape.

### 2.10.2 — 2026-10-08

- First release from this public repository. The earlier versions below
  were developed in a private repository; this one carries their final
  state, without the legacy application.
- No functional change. The architecture document
  ([Design.md](docs/Design.md)) is now public, with the analysis of the
  legacy application (§16).

### 2.10.1 — 2026-10-07

- **Caddy is the recommended web server**: the Installation Guide's web
  server chapter (§4) now sets up Caddy, which also gets and renews the
  HTTPS certificate by itself — the same routes as before, rehearsed and
  then used in a real deployment.
- Frontend package: recommends Caddy (other web servers still work), so
  installing it on a server that runs Caddy no longer brings in another
  web server; it no longer tries to reload a web server after installing
  (files are read fresh on every request).

### 2.10.0 — 2026-10-06

- **Docker image**: one container with the backend, the web application
  and Caddy, a web server that gets and renews its own Let's Encrypt
  certificate. Intel and ARM. All data in one folder outside the
  container; token secrets generated automatically on first start; can
  also run behind an existing web server. See the Installation Guide's
  new Docker chapter (§6), and `docker/compose.example.yml`.
- **New settings, also usable with the Debian packages**:
  `MPD_INITIAL_ADMIN_PASSWORD` (password of the `admin` account created
  on first start), `MPD_SECRETS_FILE` (keep generated token secrets in a
  file), `MPD_BACKUP_MARKER_FILE` (refuse to back up unless a marker file
  exists on the backup target).
- **Fix**: Admin → Backup said "not mounted — doing nothing" whenever the
  backup folder wasn't a separate mount, even with the mount check
  switched off (`MPD_BACKUP_REQUIRE_MOUNT=false`), where backups do run.
- The restore script's closing advice now also covers a container.

### 2.9.0 — 2026-10-06

- **Share a search result**: from the desktop Search screen's control
  bar, or the mobile Share button while browsing search results. The
  link is a snapshot of the pictures found (at most 50 by default, an
  admin setting; above it the first ones are shared, with a warning).
  A picture deleted since simply drops out; videos are left out. Each
  picture keeps its own sequence comment. The current picture of a
  search result can be shared too.
  *Upgrading*: add `search` to the Apache crawler rule
  (`(image|sequence|search)`, Installation Guide §4.2) so messaging apps
  show the photo in their link preview; the database migration (V14)
  runs automatically.
- **Move a sequence to another date**: the sequence popup's Rename form
  now also has a Date field (`MM/YYYY`); the whole sequence moves to that
  month in the tree, and an emptied old month/year disappears. Errors
  (bad date, name already taken) are shown in the form itself.
- **HD mode** in the desktop viewer (Gallery and Search): a new HD button
  shows pictures at full resolution until switched off.
- **Batch Publish for scanned photos**: a new option takes each picture's
  date from `year/month/sequence` folders sorted by hand, ignoring the
  scans' own dates. **Fixes**: the month field no longer pulls later
  EXIF dates back to it (it's only a fallback), and files inside hidden
  folders (e.g. `.thumbnails`) are no longer imported.
- Browser tab title is now just "myPhotoDiary"; the Admin Users and
  Index management tables keep their header visible while scrolling.
- **Fix**: sequence names typed in Rename and in the Publish forms are
  now validated (a `/` or `..` used to misplace the sequence).

### 2.8.0 — 2026-09-28

- **Adaptive photo upload**: on a slow connection, JPEG photos are made
  smaller in the browser before they're sent (never below 2400 px on the
  long side), keeping capture date and GPS. The browser measures the real
  upload speed within a few seconds and restarts a full-size upload in the
  smaller size when that's faster. Driven by a new admin setting, "Target
  average upload time per photo" (default 10 s). Desktop and mobile.
- **One control bar** on the desktop Gallery picture, left to right:
  sequence link, picture link, a new **Comment** button (shows the
  post-it), Play, Edit, Delete, Full-screen — replacing the separate top
  and bottom bars.
- **Version display**: Admin → Configure shows the version running on the
  server.
- New bottom bar carrying the "Powered by myPhotoDiary" banner.
- **Fix**: capture times read from EXIF were stored 1–2 hours late
  (the camera's time was treated as UTC). Fixed for new uploads and
  re-indexes; dates already stored are left unchanged.
- Documentation updated for all of the above.

### 2.7.1 — 2026-09-26

- **Picture editor** (desktop): rotate, crop, and perspective transform,
  each previewed before Save or Cancel. Rotate can be clicked several
  times and saved as a single rotation.
- **Fix**: a race when saving an edit could corrupt the stored picture.

### 2.7.0 — 2026-09-25

- **Mobile Share button**: shares the current picture or sequence through
  the phone's own share sheet.
- **Link previews**: shared links show the actual picture in messaging
  apps (WhatsApp and others) instead of a generic icon.
- Simpler Publish form: one "Sort by date and create a new sequence"
  checkbox, otherwise "Store photos in:" followed by the current sequence.

### 2.6.0 — 2026-09-23

- **Backup and restore**: nightly database snapshots and a daily copy of
  the original photos/videos to a separate disk or NAS, with restore
  scripts (dry-run by default).
- **Session-cookie sign-in**, replacing HTTP Basic authentication.
- Sequence geolocation on an OpenStreetMap map, showing each picture's
  own GPS position.
- Progress bars while the app and pictures load on slow networks.
- **Fixes**: uploading several files into a new sequence could fail; the
  photo backup could delete its own newest snapshot.

### 2.5.0 — 2026-09-15

- **Group-based access**: a sequence can be moved to another group, and
  seeing a sequence's pictures requires a role in its group.
- Translated, temporary "Forbidden access" message.
- Mobile corner buttons visible by default after sign-in.
- Installation Guide and User's Manual published.

### 2.4.1 — 2026-09-11

- Desktop Search: filters moved to the sidebar; download the original
  pictures of a search result, or just the current one, into a folder.
- An empty search is no longer run.
- **Fix**: the mobile mosaic view flickered on large sequences.

### 2.4.0 — 2026-09-09

- **Mobile mosaic view**: pinch out to see the whole sequence as a grid
  of thumbnails; double-tap to show or hide the corner buttons.

### 2.3.0 — 2026-09-09

- Writers can edit their own settings from the Admin tab.
- Spanish user interface.
- Android: turning the phone to landscape goes full-screen on the next tap.
- **Fixes**: iPad photo and video capture; shared links not found behind
  Apache.

### 2.2.0 — 2026-09-03

- **Share links**: a 30-day, read-only link to one picture or a whole
  sequence, for people without an account.
- Picture rotation; free-text search (sequence name and comments);
  star-rating search filter; new tags created directly from the edit
  forms; larger mobile controls on tablets; mobile "Film" screen.

### 2.1.0 — 2026-09-01

- **Batch Publish**: import a whole external folder (e.g. a memory card),
  sorted by capture date.
- Debian (`.deb`) packages for the backend and the frontend.
- **Fixes**: capture date detection for more cameras and phones; a crash
  of the mobile app when Chrome translated the page.

### 2.0.0 — 2026-08-30

- First release of the rebuilt application (Spring Boot + React),
  replacing the 2014 Servlet/JSP version: desktop and mobile interfaces,
  gallery with filmstrip and post-it comments, photo and video upload,
  tags, search, user/group/role administration, English, French and
  German, and an import tool for the legacy database.
