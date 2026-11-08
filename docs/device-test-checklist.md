# Device test checklist

For the device check in the roadmap's remaining-work table. It covers what automated tests can't: native
modules, the camera and GPS, real file I/O, maps and playback. Work through it on **one iPhone with
iOS 18**, **one with iOS 17** (or a simulator), and **one Android phone**, each on a fresh development
build. Tick each line, and note the build and the device for anything that fails.

_Covers roadmap 3.7–3.11, 4.1, 4.2, 5.1 and 5.2 (as of 2026-11-08)._

## 0. Build and setup

- [ ] `cd client && npm ci` succeeds, and `npx expo-doctor` passes.
- [ ] Development build:
  - `npx expo run:ios`, or `npx eas-cli@latest build --profile development`.
  - Android needs `GOOGLE_MAPS_ANDROID_API_KEY` in `.env.local` or the EAS secrets, restricted to package
    `app.lock.client` and the build's SHA-1.
- [ ] The API runs with `LOCK_RATE_LIMIT_STORE=postgres` (as in production) and migrations up to `V21`
      apply on a copy of real data.
- [ ] Two accounts, **A** and **B**. A has two devices (**A1**, **A2**); B has one (**B1**).

## 1. App shell (SDK 58, minimalist UI)

- [ ] Sign-in, home, vault timeline, memory, settings and members render in light and dark mode, with no
      clipped text at the largest system font size.
- [ ] The back button shows only the arrow; buttons and cards shrink slightly when pressed.
- [ ] Rotating to landscape on a tablet keeps content centred (max width).

## 2. Sign-in lockout (1.7)

- [ ] 5 wrong passwords from A1: the 6th try from A1 (even with the right password) gets "Too many attempts".
- [ ] At the same moment, the right password from A2 on a different network (mobile data) signs in.
- [ ] After a password reset by email, A1 can sign in again immediately.

## 3. Sealed Vault keys (3.8, 3.9)

- [ ] On A1, set up keys:
  - "Summer2026!!" shows "Too easy to guess", and Create stays disabled.
  - A passphrase containing A's name or email shows the name/email hint.
  - "maple river cloud tiger"-style passphrases show "Strong".
- [ ] The recovery phrase "Copy" button: paste works at once; after 60 s the clipboard is empty.
- [ ] Settings → Sealed Vault keys shows a fingerprint. Compare it with B's view of A under Members:
      they match.
- [ ] A creates a sealed shared vault and invites B. B1 can read A's first memory.
- [ ] Members on B1 shows A's key with "Mark as verified". Marking it shows "Verified in person".
- [ ] **Changed key.** On a test server, replace A's public key in `user_key_bundles` (dev only, triggers
      bypassed). Then:
  - B1's Members shows "Their key changed" with the old and new fingerprints.
  - B1 refuses to share or create keys until "accept the new key" is confirmed.
  - Restore the key afterwards.
- [ ] Remove B from the vault, then reopen it on A1: a new key version is created, and memories write
      again.

## 4. Device approval (3.6e, unchanged, re-check on SDK 58)

- [ ] On A2 (keys locked), choose "Use another device instead", and a QR appears. Scan it from A1, approve,
      and A2 unlocks within a few seconds.

## 5. Offline sealed drafts and media (3.9, 3.10)

- [ ] On A1 in airplane mode, compose a memory in the sealed vault. It shows "Waiting to sync: 1 draft".
- [ ] Add a photo and a 200 MB+ video to an online draft, then go to airplane mode. The pending list shows
      2 files.
- [ ] Kill the app, reopen it, and turn airplane mode off. Draft, photo (with thumbnail) and video all
      arrive, and the pending list empties.
- [ ] While the files are queued, look in the app's documents folder (Xcode → Devices → Download
      Container): only `sealed-*.lkm` files, no `.jpg` or `.mov`.
- [ ] Remove a member while A1 is offline with a queued draft, then reconnect. The draft syncs (it was
      re-encrypted) or asks to open the vault. It's never lost silently.

## 6. Playback and export (3.7)

- [ ] Sealed video and audio play inside the app; "Done" closes the player.
- [ ] Export a sealed vault holding a video larger than the free RAM you'd expect (500 MB+). The ZIP
      shares, and the app doesn't crash.
- [ ] After export, and after killing the app mid-export, the cache holds no `lock-export-*.zip` or
      `sealed-*` files (check the container after the next launch).

## 7. Re-index older sealed memories (3.7)

- [ ] For a memory created by an older build (no search tokens), open the vault once, then search for a
      word from its title. It's found.

## 8. Memory map (5.1, 5.2)

- [ ] Draft form → "Use my location": the iOS/Android permission prompt says "only when you ask it to pin
      a memory". Allow it, and a pin appears as degrees N/S, E/W.
- [ ] "Find ‘Where’ on the map" with a real address pins it. Gibberish shows "Couldn’t find …".
- [ ] "Pick on the map" (5.2): pan under the crosshair and tap "Pin here", and the pin matches. On iOS 18
      and Android, tapping the map moves there.
- [ ] Vault → Map:
  - Pinned memories appear.
  - Tapping a marker opens the memory on iOS 18 and Android.
  - On iOS 17 the list under the map opens it.
- [ ] Many pins in one city collapse into one marker with a count when zoomed out (5.2). Tapping it zooms
      in, and the pins separate.
- [ ] "Place by ‘Where’" places older unpinned memories, marked "placed from ‘Where’". It's off again
      after the app restarts.
- [ ] Home → "Map of all memories" (5.2) shows pins from every vault. A locked sealed vault is listed as
      "unlock to include".
- [ ] A closed time capsule never appears on any map.
- [ ] Android without the Maps key: the map area is blank, but the list still works and nothing crashes.
- [ ] Web (`npx expo start --web`): the map screens show the list only, with no errors.

## 9. Reporting

For each failure, record:
- the checklist line;
- the device, OS version and build number;
- the steps;
- a screenshot or screen recording.

File each failure as an issue, and link the issues from the roadmap's remaining-work table.
