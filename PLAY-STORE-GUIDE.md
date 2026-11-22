# CheckLink – Google Play checklist

## 0) Before anything
1. Upload app/ folder (from checklink-app.zip) to GitHub `neww/app/` → privacy policy URL becomes
   https://zeroxslam.github.io/neww/app/privacy.html  (put YOUR email inside privacy.html first).

## 1) Build the signed AAB (no install needed)
1. New GitHub repo, upload this whole folder (incl. hidden .github).
2. Actions → "Create signing key (run ONCE)" → Run. Download artifact SIGNING-KEY-KEEP-SAFE.
   KEEP release.jks + secrets.txt SAFE (a lost key = you cannot update the app). Use a PRIVATE repo.
3. Repo Settings → Secrets and variables → Actions → add 4 secrets:
   KEYSTORE_B64 (content of release.jks.b64), KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS (= checklink).
4. Actions → "Build release" → Run. Download CheckLink-release → app-release.aab (upload to Play) + app-release.apk (install to test).

## 2) Play Console (play.google.com/console, one-time 25 USD)
* Create app → Production (or Internal testing first).
* Store listing: use STORE-LISTING.txt, icon play-store-icon-512.png, 2+ phone screenshots, feature graphic 1024x500.
* App content: Privacy policy URL; Data safety form (see below); Target audience 13+; Ads = No.
* Sensitive permission declaration: Notification access → core purpose: scam/phishing protection; explain that
  text stays on device. The app shows an in-app disclosure before opening Android settings.
* New personal accounts must run a closed test (12+ testers for 14 days) before production.

## Data safety answers (match privacy.html)
* Data collected & shared: "Web browsing history/URLs" → shared with VirusTotal, purpose App functionality/Security, not optional per scan.
* Messages: processed on device only, not collected.
* Data encrypted in transit: Yes. Users can request deletion: no data stored by us.
