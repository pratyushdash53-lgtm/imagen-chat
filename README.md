# Imagen Chat

A clean, minimal Android app for generating images with any **OpenAI-compatible** API.
It looks and feels like a chat: describe an image, get the image back as the reply.

- **Chat UI** with a hamburger drawer for history (stored on-device) and a New chat button
- **Settings** for your API base URL and key (the key is stored encrypted)
- **Finds models for you**: lists `/models` at your URL and picks the best image model
  (`gpt-image-2.5-flare`, `gpt-image-2.5-sunburst`, `gpt-image-1.5`, `gpt-image-1`, `gpt-image-1-mini`),
  or lets you choose any listed model
- **Transparent PNGs**: sends the right parameters per model family
  (`background: transparent` for `gpt-image-*`), verifies real alpha, and falls back to a local
  cut-out when the provider returns a plain backdrop. Transparent results sit on a checkerboard
- **Chat memory**: a text model (`gpt-6.1-sol` when your provider has it, or any you choose) reads the
  conversation so follow-ups like "remove the outline" edit the previous image instead of starting over
- **Reference images**: attach a photo, or tap Edit on any result, and the app uses the `/images/edits` endpoint
- **Rename chats** from the history menu or by tapping the title
- **Generating animation**: an aurora card with live status while the image is made
- Save to `Pictures/ImagenChat`, share, full-screen view, retry on error

Request handling mirrors the bundled Python tool (`image_service.py`): provider errors are
summarised, never echoed with headers or keys, and image URLs are downloaded without your key.

## Install

Download the latest `imagen-chat-*.apk` from [Releases](../../releases) and open it on your phone
(allow "Install unknown apps" for your browser or file manager). Requires Android 10+.

## Build

Pushes and pull requests build an APK in GitHub Actions (see the run's artifacts).

To release, bump the number in `VERSION` and merge to `main`: Actions builds the APK and publishes a
GitHub Release `v<VERSION>` with the APK attached. Pushing a `v*` tag does the same.

Locally: `./gradlew assembleRelease` (JDK 17 and the Android SDK).

### Stable signing key (recommended)

Without secrets, release APKs are signed with a throwaway debug key, so each build has a different
signature and phones will not update one build over another. To fix that, create a keystore once and
add these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

```bash
keytool -genkeypair -v -keystore release.jks -alias imagen -keyalg RSA -keysize 2048 -validity 10000
```
