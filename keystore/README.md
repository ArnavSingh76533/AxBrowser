# CI Release Keystore

`ci-release.keystore` is committed to this repo on purpose. It is **not**
a secret production signing key - it exists purely so every GitHub Release
build is signed with the *same* certificate, which Android requires for
in-place updates (installing a new APK over the currently running one
without uninstalling first). Previously the release workflow generated a
brand-new random keystore on every run, which meant every release had a
different signature and Android would refuse to update in place.

Alias: `axbrowser`
Store password / key password: `axbrowser123`

**If you ever publish this app to the Play Store**, generate a real,
private release key and store it via GitHub Actions secrets instead -
don't reuse this one. This keystore is only appropriate for sideloaded/
direct-APK-download distribution like the GitHub Releases page.
