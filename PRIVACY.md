# Privacy Policy — YomiDojo

**Last updated: 2026-07-30**

YomiDojo is a manga and webtoon reader for Android. This page explains what data the app
accesses, where it goes, and what control you have over it.

## The short version

YomiDojo is local-first. It reads manga files you already have — on your device, a network
share, OneDrive, or Google Drive — and keeps its library database, covers, and settings on your
device. Nothing is sent to a server operated by the developer. There are no accounts with
YomiDojo itself, no ads, and no analytics or tracking SDKs.

## Data the app accesses

### Your manga files
When you point YomiDojo at a folder (on your device, a network share, OneDrive, or Google
Drive), the app reads that folder's contents — file and folder names, and the manga page images
themselves — to build your library. This never leaves your device except to the specific
storage location you connected (e.g. your own OneDrive/Google Drive account, or your own
network share), which you access directly with your own credentials.

### Network share (SMB) credentials
If you connect a network share, the username and password you enter are encrypted and stored
only on your device (via the Android Keystore), and used only to connect to that specific
server. They are never sent anywhere else.

### Google or Microsoft sign-in
Signing in with Google or Microsoft is optional, and only needed if you want to:
- browse and read manga stored in your Google Drive or OneDrive, and/or
- sync your reading progress, favorites, and metadata corrections across your own devices
  (Google account only).

For sync, YomiDojo stores this data in a private, app-only area of **your own** Google Drive
(Drive's `appDataFolder`) — it is not visible in your regular Drive file list, is not shared
with anyone, and is never seen or stored by the developer. A randomly generated device
identifier is included with this data solely to resolve conflicts when the same series is read
on more than one device; it is not personally identifying and is not sent anywhere else.

Signing in uses each provider's own standard OAuth login. YomiDojo never sees or stores your
Google or Microsoft password.

### Metadata lookups (AniList, Kitsu)
To show cover art, descriptions, genres, and ratings for a series, YomiDojo sends the series
title (derived from your file/folder names) to the public AniList or Kitsu API. No account or
sign-in with either service is required or used for this. These are third-party services with
their own privacy policies:
- AniList: https://anilist.co/terms
- Kitsu: https://kitsu.io/terms

## What YomiDojo does not do

- No advertising, and no ad networks.
- No analytics or crash-tracking SDKs.
- No data is sold, shared with advertisers, or used for profiling.
- No account system of its own — nothing to sign up for, nothing stored on a developer-operated
  server.

## Permissions

YomiDojo requests Android's network permission (`INTERNET`), used only for the connections
described above (your own cloud/network storage, and the AniList/Kitsu metadata lookups).
Access to local files uses Android's Storage Access Framework, which requires you to explicitly
pick a folder — YomiDojo cannot browse your device's storage beyond what you've chosen.

## Children's privacy

YomiDojo does not knowingly collect data from children and has no account system that could
process such data. Manga libraries are supplied entirely by the user, so content appropriateness
depends on what you add to your own library.

## Changes to this policy

If this policy changes, the update will be posted here with a new "last updated" date.

## Contact

Questions about this policy: olihey@googlemail.com
