# Welcome photo/video

Put the welcome photo (or video) in this folder, for example `welcome.jpg`.

- The backend `.deb` package ships every file here (this README excepted) to
  `/usr/share/myphotodiary-backend/welcome/`.
- On a **first install only**, the package copies them to the root of the
  photo storage (`MPD_STORAGE_ROOT`), where they show up as the photos of the
  root folder.
- Upgrades never copy them again, so users can replace or delete them.
- Nothing is copied if the storage root already contains files.
