# Third-Party Notices

myPhotoDiary itself is licensed under the Apache License, Version 2.0 (see
[LICENSE](LICENSE)). It includes, unmodified, the third-party components
listed below, each distributed under its own license. Their copyright
notices and full license texts are the ones published by each project (at
the address given, or inside the component itself - every backend library
jar carries its own license and notice files).

This list covers myPhotoDiary 2.10.4 and was generated from the build's
actual dependencies, not written by hand. To regenerate it:

```bash
# backend - runtime dependencies bundled in the jar
cd backend && mvn org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party \
  -Dlicense.excludedScopes=test,provided,system
#   -> target/generated-sources/license/THIRD-PARTY.txt
# frontend - production dependencies bundled in dist/
cd frontend && npm ls --omit=dev --all
```

## Frontend (bundled into the static web files)

| Component | Version | License |
|---|---|---|
| [react, react-dom, scheduler, use-sync-external-store](https://github.com/facebook/react) | 19.2.8 / 0.27.0 / 1.6.0 | MIT |
| [filepond](https://github.com/pqina/filepond) | 4.32.12 | MIT |
| [react-filepond](https://github.com/pqina/react-filepond) | 7.1.3 | MIT |
| [i18next](https://github.com/i18next/i18next) | 26.4.0 | MIT |
| [i18next-browser-languagedetector](https://github.com/i18next/i18next-browser-languageDetector) | 8.2.1 | MIT |
| [react-i18next](https://github.com/i18next/react-i18next) | 17.0.12 | MIT |
| [html-parse-stringify](https://github.com/i18next/html-parse-stringify) | 4.0.1 | MIT |
| [@babel/runtime](https://github.com/babel/babel) | 7.29.7 | MIT |
| [leaflet](https://github.com/Leaflet/Leaflet) | 1.9.4 | BSD-2-Clause |

Icons drawn in the frontend's own code reproduce glyphs from two icon sets:

| Icon set | Used for | License |
|---|---|---|
| [Material Design Icons](https://github.com/google/material-design-icons) (Google) | Desktop viewer control bar (folder, link, sequence, comment, crop) | Apache-2.0 |
| [Feather Icons](https://github.com/feathericons/feather) | Mobile interface icons | MIT |

## Backend (bundled into the application jar)

Most of these are Apache-2.0, MIT or BSD. A few are under weak-copyleft
licenses - Hibernate ORM (LGPL-2.1-or-later), Logback (EPL-2.0 or
LGPL-2.1), AspectJ (EPL-2.0), and some Jakarta APIs (EPL-2.0 or GPL-2.0
with the Classpath Exception; EDL-1.0) - and are included as unmodified
libraries, their source available from each project.

| Component (group:artifact) | Version | License |
|---|---|---|
| `ch.qos.logback:logback-classic` | 1.5.34 | EPL-2.0 / LGPL-2.1-only |
| `ch.qos.logback:logback-core` | 1.5.34 | EPL-2.0 / LGPL-2.1-only |
| `com.adobe.xmp:xmpcore` | 6.1.11 | The BSD 3-Clause License (BSD3) |
| `com.drewnoakes:metadata-extractor` | 2.18.0 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.4 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | Apache-2.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` | 2.21.4 | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.21.4 | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.21.4 | Apache-2.0 |
| `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.21.4 | Apache-2.0 |
| `com.fasterxml:classmate` | 1.7.3 | Apache-2.0 |
| `com.sun.istack:istack-commons-runtime` | 4.1.2 | Eclipse Distribution License - v 1.0 |
| `com.zaxxer:HikariCP` | 6.3.3 | Apache-2.0 |
| `io.micrometer:micrometer-commons` | 1.15.12 | Apache-2.0 |
| `io.micrometer:micrometer-observation` | 1.15.12 | Apache-2.0 |
| `io.smallrye:jandex` | 3.2.0 | Apache-2.0 |
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | EDL 1.0 |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL 2.0 / GPL2 w/ CPE |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 |
| `jakarta.persistence:jakarta.persistence-api` | 3.1.0 | Eclipse Distribution License v. 1.0 / Eclipse Public License v. 2.0 |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | EPL 2.0 / GPL2 w/ CPE |
| `jakarta.validation:jakarta.validation-api` | 3.0.2 | Apache-2.0 |
| `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.5 | Eclipse Distribution License - v 1.0 |
| `net.bytebuddy:byte-buddy` | 1.17.8 | Apache-2.0 |
| `net.coobird:thumbnailator` | 0.4.20 | MIT License |
| `org.antlr:antlr4-runtime` | 4.13.2 | BSD-3-Clause |
| `org.apache.logging.log4j:log4j-api` | 2.24.3 | Apache-2.0 |
| `org.apache.logging.log4j:log4j-to-slf4j` | 2.24.3 | Apache-2.0 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.55 | Apache-2.0 |
| `org.apache.tomcat.embed:tomcat-embed-el` | 10.1.55 | Apache-2.0 |
| `org.apache.tomcat.embed:tomcat-embed-websocket` | 10.1.55 | Apache-2.0 |
| `org.aspectj:aspectjweaver` | 1.9.25.1 | Eclipse Public License - v 2.0 |
| `org.eclipse.angus:angus-activation` | 2.0.3 | EDL 1.0 |
| `org.flywaydb:flyway-core` | 11.7.2 | Apache-2.0 |
| `org.flywaydb:flyway-database-hsqldb` | 11.7.2 | Apache-2.0 |
| `org.glassfish.jaxb:jaxb-core` | 4.0.9 | Eclipse Distribution License - v 1.0 |
| `org.glassfish.jaxb:jaxb-runtime` | 4.0.9 | Eclipse Distribution License - v 1.0 |
| `org.glassfish.jaxb:txw2` | 4.0.9 | Eclipse Distribution License - v 1.0 |
| `org.hibernate.common:hibernate-commons-annotations` | 7.0.3.Final | Apache-2.0 |
| `org.hibernate.orm:hibernate-core` | 6.6.53.Final | GNU Library General Public License v2.1 or later |
| `org.hibernate.validator:hibernate-validator` | 8.0.3.Final | Apache-2.0 |
| `org.hsqldb:hsqldb` | 2.7.3 | HSQLDB License, a BSD open source license |
| `org.jboss.logging:jboss-logging` | 3.6.3.Final | Apache-2.0 |
| `org.slf4j:jul-to-slf4j` | 2.0.18 | MIT |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT |
| `org.springframework.boot:spring-boot` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-autoconfigure` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-data-jpa` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-jdbc` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-json` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-logging` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-security` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-tomcat` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-validation` | 3.5.16 | Apache-2.0 |
| `org.springframework.boot:spring-boot-starter-web` | 3.5.16 | Apache-2.0 |
| `org.springframework.data:spring-data-commons` | 3.5.13 | Apache-2.0 |
| `org.springframework.data:spring-data-jpa` | 3.5.13 | Apache-2.0 |
| `org.springframework.security:spring-security-config` | 6.5.11 | Apache-2.0 |
| `org.springframework.security:spring-security-core` | 6.5.11 | Apache-2.0 |
| `org.springframework.security:spring-security-crypto` | 6.5.11 | Apache-2.0 |
| `org.springframework.security:spring-security-web` | 6.5.11 | Apache-2.0 |
| `org.springframework:spring-aop` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-aspects` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-beans` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-context` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-core` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-expression` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-jcl` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-jdbc` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-orm` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-tx` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-web` | 6.2.19 | Apache-2.0 |
| `org.springframework:spring-webmvc` | 6.2.19 | Apache-2.0 |
| `org.yaml:snakeyaml` | 2.4 | Apache-2.0 |

## Docker image (additional components)

The Docker image (`docker/Dockerfile`) contains the application above
plus the following, unmodified. Unlike the `.deb` packages, which leave
them to the server's own package manager, the image redistributes them:

| Component | Version in the image | License | Source |
|---|---|---|---|
| Ubuntu base system (`eclipse-temurin:21-jre-noble`) | Ubuntu 24.04 | Various, per package — each package's copyright file is in the image under `/usr/share/doc/<package>/copyright` | Ubuntu archive (`apt-get source <package>`) |
| [Eclipse Temurin](https://adoptium.net/) OpenJDK runtime | 21.0.12.1 | GPL-2.0 with the Classpath Exception | <https://github.com/adoptium/jdk21u> |
| [FFmpeg](https://ffmpeg.org/) (`ffmpeg`, `ffprobe` and their libraries) | 6.1.1, Ubuntu package `7:6.1.1-3ubuntu5` | GPL-2.0-or-later (Ubuntu's build enables FFmpeg's GPL parts) | Ubuntu archive (`apt-get source ffmpeg`) |
| [Caddy](https://caddyserver.com/) web server | 2.11.7 | Apache-2.0 | <https://github.com/caddyserver/caddy> |
| [tini](https://github.com/krallin/tini) | 0.19.0 | MIT | <https://github.com/krallin/tini> |
| [rsync](https://rsync.samba.org/) | 3.2.7 | GPL-3.0-or-later | Ubuntu archive (`apt-get source rsync`) |

The GPL-licensed components are used as separate programs (the backend
runs `ffmpeg`/`ffprobe` and the backup scripts run `rsync` as external
commands), not linked into myPhotoDiary's own code, which stays under the
Apache License 2.0.

## Not distributed with myPhotoDiary

These are used at runtime but are **not** part of the myPhotoDiary
packages - they come from the server's own operating system or from an
online service:

- **OpenJDK 21** and **FFmpeg** (`ffmpeg`/`ffprobe`), for the `.deb`
  packages - installed by the operating system's package manager as
  dependencies of the backend package, under their own licenses (GPL-2.0 with the Classpath
  Exception for OpenJDK; LGPL/GPL for FFmpeg, depending on the build).
- **OpenStreetMap** map tiles, loaded by the browser for the sequence
  geolocation map - map data © OpenStreetMap contributors, available
  under the Open Database License (ODbL); the attribution is displayed
  on the map itself.
