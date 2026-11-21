# Starksky TV

A clean, fast Plex client for Android TV (built for the NVIDIA SHIELD).

- Plex PIN sign-in (plex.tv/link), Plex Home profiles with PIN support
- Home: Continue Watching, New TV, New Movies (+ one row per extra library)
- TV Shows / Movies / other libraries as clean poster grids
- Detail pages with Play / Resume / Start over, Trailer, seasons + episodes
- Direct play only (no transcoding), subtitles + audio track picker, skip intro, auto next episode
- Progress syncs back to Plex, so resume points match your other Plex apps

## Build
Push to GitHub; the workflow in `.github/workflows/build.yml` builds `app-debug.apk` and
publishes it on the repo's Releases page.

Direct link to the newest build:
`https://github.com/<user>/<repo>/releases/latest/download/app-debug.apk`

## Install on the SHIELD
1. Settings > Device Preferences > Security & restrictions > allow "Unknown sources" for the Downloader app
2. Install the "Downloader" app from the Play Store on the SHIELD
3. Open Downloader, enter the direct link above, install

## New Home screen, weather and Spotify

- Home shows the selected title's artwork full-screen, with the clock and weather (Open-Meteo, no key; set to Columbia City in `Prefs.kt`).
- Accent color follows the artwork of the selected title.
- **Spotify** chip in the top bar opens the Spotify TV app.
- **Now playing / lyrics:** Settings → "Allow now playing (Spotify)" opens Android's notification-access screen. Turn Starksky TV on there. Synced lyrics come from LRCLIB.
- **Visualizer** (on the Now Playing screen) is an experiment: it asks Android for permission to capture the music app's audio. Spotify or the SHIELD may block it, in which case the screen says so.

## Search, watched, trailers

- **Search** (top bar): type with the on-screen keyboard (press OK on the box) or tap **Speak** to use the remote's mic. Searches every movie and show library.
- **Mark watched / unwatched** is on every title page (movie, whole show, season, each episode) and on Continue Watching / Next Up items on Home. It is written to Plex, so it syncs everywhere. Home re-syncs with Plex every 90 seconds; Settings has **Sync from Plex now**.
- **Trailer backup:** titles with no Plex trailer fall back to TMDB, played in the YouTube app. Needs a free TMDB key: put it on the empty line in `app/src/main/assets/tmdb_key.txt`. (IMDb has no public trailer feed, so Plex's IMDb ids are used to find the title on TMDB.)
- **Opening:** Settings → "Opening plays" switches between every launch and first launch each day.

## Requests and suggestions (Overseerr)

- Set the Overseerr address in `app/src/main/assets/overseerr.txt` (defaults to `http://192.168.1.8:5055`).
- **Discover** tab: "Because you watched …" rows built from the selected profile's recent Plex watches, plus Trending and Recent requests. Anything already available or requested is hidden.
- **Request** from a Discover card, or from Search (titles not in your library appear under "Not in your library"). Requests are sent to Overseerr, which hands them to Sonarr/Radarr.
- Signs in to Overseerr with the selected profile's Plex account. Managed profiles that Overseerr doesn't know fall back to the account owner, or to the API key if you add one to `overseerr.txt`.

## Watchlist

- Home has a **Watchlist** row: the same Plex Watchlist every Plex app shows for the selected profile. Titles you already have open their library page; the rest open the request page.
- **Add to / Remove from Watchlist** is on every title page and on the request page. It is written to Plex, so it syncs both ways.
- Profiles are your Plex Home users (pick one at launch). Add or remove a user in Plex and the change shows up here.

## Auto-update

- Each push to GitHub builds a new numbered release (build 2, 3, …). The app checks the latest release every time it opens. When a newer build exists, an **Update** chip appears in the top bar, and Settings shows **Install update**.
- Android always asks you to press **Install** once; apps can't install themselves silently.
- All builds are signed with the key in `app/starksky-debug.keystore`, so new builds install over old ones without uninstalling. Don't delete it.
- The repo must be public for the app to read releases without a login.
- Optional: add your TMDB key as a repo secret named `TMDB_KEY` (Settings → Secrets and variables → Actions) instead of committing it.
