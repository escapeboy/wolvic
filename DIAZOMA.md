# Diazoma Browser

This branch is Wolvic with the Diazoma layer applied: Diazoma Browser for Meta Quest, a Wolvic build with the Chromium
backend that opens web pages as Diazoma walls (https://diazoma.app/docs/browser/).

- The branch is generated: the layer (new files under `com.igalia.wolvic.stena`, the brand images, the terms and
  privacy screens, and small edits to existing Wolvic files) is applied by a script to the Wolvic commit this branch
  starts from. Each edit is marked with a `Stena:` comment.
- `app/src/main/assets/stena/` holds the built scripts the browser injects into pages.
- The Chromium side is the `diazoma` branch of https://github.com/escapeboy/wolvic-chromium. Build it as Wolvic's
  `CHROMIUM.md` describes, then `./gradlew assembleOculusvrArm64ChromiumGenericRelease`.

Diazoma Browser is not made or endorsed by Igalia. Wolvic is licensed under the Mozilla Public License 2.0, and so are
the changes on this branch.
