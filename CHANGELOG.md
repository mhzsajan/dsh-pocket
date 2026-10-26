# Changelog

All notable changes to DSH Pocket. Versions are the APK `versionName`; every build
is signed with the same committed debug key (`keystore/debug.keystore`).

## Unreleased

- Device guard: the launcher now shows Android version + ABI and explains when the
  phone is not arm64 instead of failing inside PRoot.
- Repo: GitHub-ready layout, MIT license, third-party notices, runtime fetch script,
  docs for universal installation and the development loop.

## 1.12 — 2026-10-25

Phone-first UI pass (the "still not proper" round):

- Bottom navigation is **Chats / New / More** (Search removed). *More* is a native
  dialog with Account/Settings, Reload, Chrome, Copy URL, Restart, Stop.
- Session sidebar and right panel open as overlay drawers with a scrim; the right
  panel actually opens now (it was pinned shut by the skin's grid override).
- Drawers sit above the bottom nav so the footer/account menu is tappable; the
  account popover becomes a bottom sheet.
- Settings dialog: sticky close button, desktop-only "Open configuration file"
  hidden, tab strip padded away from the close button.
- Sign-in/API-key dialogs readable, actions share the row, no clipped primary button.
- Thumb-sized targets (40–48 px), 44 px header/right-panel icons, gesture-safe nav
  height, keyboard hides the nav and keeps the composer above the IME.
- **Fixed a page freeze**: the placeholder MutationObserver compared lengths, so it
  rewrote the same node forever and blocked taps. Now compares exact strings.
- **Fixed stuck "Loading…"**: skin is injected on `onPageCommitVisible` and by a
  3.5 s fallback, so a pending load event cannot leave the WebView hidden.
- Short composer placeholder; hero re-centred; placeholder re-applied safely.

## 1.11 — 2026-10-25

- Native Go-to-menu dialog instead of an off-screen PopupMenu.
- Initial drawer/right-panel auto-close retried (DSH restores panel state late).

## 1.10 — 2026-10-25

- Back button closes dialogs/panels first (JS `__dshpBack` + Java bridge).

## 1.9 — 2026-10-25

- Scrim stacking fixed (drawer no longer dimmed).

## 1.8 — 2026-10-25

- Freeze fix in the placeholder observer.

## 1.7 — 2026-10-25

- Right panel opens (`data-sidebar-right-open` detection), bottom-sheet popovers,
  tap-target pass, settings row wrapping.

## 1.6 — 2026-10-25

- Visibility fallback injection (`onPageCommitVisible` + timer), loading overlay.

## 1.5 — 2026-10-25

- Placeholder shortening + first UI polish.

## 1.4 — 2026-10-25

- Gesture-safe bottom nav, dialog button layout, settings tab fixes.

## 1.3 — 2026-10-25

- Header/tab stacking and dialog fixes; keyboard detection.

## 1.2 — 2026-10-24

- First phone-first skin: overlay drawers, bottom nav, full-width chat.

## 1.0 — 2026-10-24

- First working APK: embedded Ubuntu 24.04 arm64 + Node 22 + DSH 0.2.1-rc.2 under
  PRoot, foreground service, token-URL WebView, first-run extraction (~15 s),
  `DSH_READY` in ~29 s.
