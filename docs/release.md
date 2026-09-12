# Cutting a release

The client checks whether a newer version exists and shows a bar at the bottom
of the chat list. The bar downloads the apk from GitHub and hands it to the
system to install.

The order matters.

1. Raise the number in the client: `MargeletConfig.APP_VERSION`, and
   `APP_VERSION_CODE` in `gradle.properties` next to it. **Both live in the
   Telegram clone, not in this repository** — so after changing them, copy
   `MargeletConfig.java` back into `java/margelet/` and regenerate the patch.
   This is easy to forget and hard to notice: the release goes out correct,
   because it is built from the clone, while the repository keeps the old
   number. That is exactly what happened on 0.99.28 — the apk carried 0.99.28
   and git still said 0.99.27, so the next build from the repository would
   have gone out with the wrong number.
2. Build the apk.
3. Put it on the `apk` branch — both under its own number and as `margelet.apk`.
4. Only now raise the number in [version.json](../version.json) — **and the
   `apk` address along with it, pointing at the file with that number**.

`version.json` goes last because it is the announcement: the moment it carries a
higher number, everyone's bar appears. Announce it before the apk is in place
and people will tap it and get an error.

## version.json

```json
{
  "version": "0.2",
  "apk": "https://github.com/narezany/margelet/raw/apk/margelet.apk",
  "about": "What changed.",
  "about_ru": "Что изменилось."
}
```

| | |
|---|---|
| `version` | the latest version. Compared as numbers between dots, so `0.10` is above `0.9` |
| `apk` | where to download it from. **A link with the version number in it, not `margelet.apk`** — see below |
| `about` | one line of what changed. `_ru` and `_zh` are translations |

## Why a numbered link, not a permanent one

`margelet.apk` used to be here — one address for all time. It was shorter, and
it was wrong.

GitHub serves files from a branch through a cache, `max-age=300`. The name never
changes, which means that for the first five minutes after a release that
address can hand back the bytes of the **previous** build — silently, with a
200. The updater downloaded those, installed them, the app stayed old, the bar
came back, and round it went, forever. The owner walked into this on 1.0.14:
he updated through Margy itself and kept getting 1.0.13.

An address with the version number in it is one the cache has never seen, so it
serves the real file. That is the whole reason, and it is worth the extra line
at release time.

Forgetting to change `apk` along with `version` is the same class of mistake as
forgetting step one: people will download the previous build and wonder why the
update never ends.

`margelet.apk` stays on the branch: the README points at it, and so do people
downloading by hand. The updater no longer uses it.

## What it does not do

Nothing is downloaded or installed behind anyone's back: the download starts on
a tap, and the system installs the apk after asking. An app cannot quietly
replace itself, and that is as it should be.

A partial file does not count as ready. If the network drops or the download is
cancelled, the piece is deleted and the bar offers to download again rather than
to install something that is not there.

Forgetting step one is the expensive mistake: a build with a stale
`APP_VERSION` will offer to update itself to itself forever.

## How often the client asks

Every three minutes by default; each person changes it in the fork's settings,
from three minutes to a day, or switches it off. The "Check now" button there
works regardless, even with automatic checks off.
