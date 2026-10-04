# F-Droid

There are two independent ways to get A*Untis into F-Droid. You can use either or both.

| | Own repository (automated) | Official f-droid.org (manual submission) |
|---|---|---|
| Who builds the APK | your GitHub release workflow | F-Droid, from source |
| Signed with | **your** release key | F-Droid's key (unless reproducible builds are set up) |
| In-app GitHub updater | stays active | switched off (`fdroid=true`) |
| Setup effort | one-time secrets + Pages | merge request to fdroiddata |
| Update delay | minutes after a release | days (F-Droid build cycle) |

Both use the store texts in `fastlane/metadata/android/` and the app definition
`fdroid/metadata/com.webuntis.dashboard.yml`.

---

## 1. Own F-Droid repository (`.github/workflows/fdroid.yml`)

Whenever the existing release workflow publishes a GitHub release, `fdroid.yml` downloads the
signed universal APK (`a_untis_release_signed.apk`) of the newest 5 releases, builds the F-Droid
index with `fdroidserver`, signs the index and publishes it to GitHub Pages:

`https://<owner>.github.io/<repo>/fdroid/repo`

### One-time setup

1. **Create a repository signing key** (this signs the *index*, it is NOT your app key; keep it
   safe, losing it means everybody has to re-add the repo):

   ```bash
   keytool -genkeypair -v -keystore fdroid-repo.jks -alias fdroid-repo \
     -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 fdroid-repo.jks > fdroid-repo.jks.b64     # macOS: base64 -i fdroid-repo.jks
   ```

2. **Add GitHub secrets** (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `FDROID_KEYSTORE` | contents of `fdroid-repo.jks.b64` |
   | `FDROID_KEYSTORE_PASSWORD` | keystore password |
   | `FDROID_KEY_PASSWORD` | key password (same as above if you pressed Enter) |
   | `FDROID_KEY_ALIAS` | `fdroid-repo` |

3. **Run the release workflow once** (or *Actions → Publish F-Droid repository → Run workflow*
   with a tag such as `v0.5.7`). This creates the `gh-pages` branch.
4. **Enable GitHub Pages**: Settings → Pages → Source: *Deploy from a branch* → `gh-pages` / `/ (root)`.
5. **Add the repo in the F-Droid app**: Settings → Repositories → **+**, enter
   `https://<owner>.github.io/<repo>/fdroid/repo`. Or open the same URL on the phone and tap
   *Open in F-Droid*. Check the fingerprint shown in F-Droid against the one printed by
   `keytool -list -v -keystore fdroid-repo.jks`.

### Notes

- Trigger: `release: published`. The release workflow creates the release with `secrets.TOKEN`
  (a personal access token) – that is what lets it start this workflow; releases created with the
  default `GITHUB_TOKEN` would not.
- The APK in the repo is exactly the one attached to the GitHub release (same signature), so
  users can switch between GitHub and F-Droid updates without reinstalling. The in-app updater
  stays enabled in this build.
- **Changelog**: each version's changelog in the F-Droid app is the GitHub release notes (the
  "What's Changed" text from `generate_release_notes`), converted to plain text and cut to 500 bytes.
- Only releases tagged `vX.Y.Z` (not drafts/pre-releases) are included. The APK file name is
  `com.webuntis.dashboard_<versionCode>.apk` where
  `versionCode = major*1000000 + minor*10000 + patch` (0.5.7 → 50007).
- To publish a specific tag manually: *Actions → Publish F-Droid repository → Run workflow*.

---

## 2. Official F-Droid (f-droid.org)

F-Droid never takes uploaded APKs; it builds from a recipe in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata).

What is already prepared in this repository:

- `fastlane/metadata/android/{en-US,de-DE}/` – title, short/full description and screenshot.
  F-Droid picks these up automatically. There are no per-version changelog files: the recipe's
  `Changelog:` field links to the GitHub releases page instead.
- `fdroid/metadata/com.webuntis.dashboard.yml` – the build recipe (license, categories,
  anti-feature `NonFreeNet`, build at tag `v0.5.7`).
- `app/build.gradle.kts` – with `fdroid=true`: the in-app GitHub updater is disabled
  (`BuildConfig.SELF_UPDATE = false`, F-Droid delivers updates), ABI splits are off and the
  dependency-info blob is removed. The recipe also strips `REQUEST_INSTALL_PACKAGES`.

Steps:

1. Fork <https://gitlab.com/fdroid/fdroiddata> and copy
   `fdroid/metadata/com.webuntis.dashboard.yml` to `metadata/com.webuntis.dashboard.yml`.
2. Check locally (needs `fdroidserver`):
   ```bash
   fdroid lint com.webuntis.dashboard
   fdroid build -v -l com.webuntis.dashboard
   ```
3. Open a merge request. The F-Droid team reviews license, dependencies and anti-features and may
   ask for changes.
4. For every new release add a `Builds:` entry (new `versionName`, `versionCode`, `commit`) –
   the version code is computed in Gradle, so F-Droid cannot read it from a tag automatically.
   To get fully automatic updates (`AutoUpdateMode: Version`), put a literal `versionCode` into
   `dependencies.gradle` and set `UpdateCheckData`.
5. Optional: if you want the changelog text inside the F-Droid app of the official store, commit
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max. 500 bytes) *before* tagging –
   the GitHub release notes only exist after the tag, so they cannot be used there.

### Things to know before submitting

- **License**: `LICENCE` is the GPL-3.0 text, so the recipe says `GPL-3.0-only`. Change it to
  `GPL-3.0-or-later` if that is what you intend.
- **Anti-feature `NonFreeNet`**: the app only works with WebUntis, a proprietary service.
- **Name/trademark**: the app is unofficial; the description says so.
- **Reproducible builds / same signature**: F-Droid signs with its own key by default, so users
  cannot update between GitHub APKs and F-Droid builds. Reproducible builds
  (`Binaries:` + `AllowedAPKSigningKeys:`) are possible but need a deterministic build; not set up.
- `app/src/main/assets/adi-registration.properties` is a Google developer-verification token; it
  is harmless but reviewers may ask about it.
