# DSH Pocket — phone UI audit (WebView skin)

Auditor: android-app (read-only on `dsh-pocket/app/**` and `build.ps1`).
Method: live WebView on the phone via CDP (`dsh-pocket/tools/cdp.mjs` + a quote-safe helper
`dist/audit/_tools/cdp-file.mjs`), DOM/geometry measurements, and `adb exec-out screencap` for
native (Java) overlays. Viewport under test: **384 × 782 CSS px, DPR 2.8125** (1080 × 2200 device).

## Builds under test

| build | sha256 | installed | what was verified on it |
| --- | --- | --- | --- |
| **v1.4** (versionName 1.4, versionCode 5) | `43ac85ce29d9c3c19404f4f97cace2e95b0e553a46d84185200a05f088887045` | 18:46 | hero + nav height, sidebar drawer, right sidebar, keyboard state, sign-in dialog |
| v1.3 (versionName 1.3, versionCode 4) | `fc040df3…91205235` | 18:34 | settings dialog + tabs, model/workspace/preset menus, native "More" popup, Trajectory, chat scroll, add-API-key dialog |

⚠️ During the v1.4 session the app process was restarted several times by external activity on the
shared phone (pids 13663 → 14861 → 17246 → 19131, no crash in `logcat -b crash`). v1.4 re-checks
below are the ones that completed; items marked **(v1.3 evidence)** still need a v1.5 spot-check.

Severity: **P0** unusable/blocking · **P1** high friction / clearly broken · **P2** polish.

---

## 1. Hero / empty session (v1.4)

Screenshot: `dist/audit/v14-01-hero.png`

- ✅ v1.4 fixes confirmed: bottom nav is 74 px with labels fully visible (`#dshp-nav`, measured
  `h=74`), hero composer re-centred, single correct placeholder (no duplicate ghost text).
- **P2** Rightbar header icon button overflows the viewport: `button[aria-label="Open right
  sidebar"]` rect `x=354 w=40` → right edge **394 > 384** (10 px clipped). Fix: reduce
  `[data-slot="conversation.session.header.utilities"]` right padding / shift icons left.
- **P1** Small tap targets (see §12): `button[aria-label="Choose workspace"]` 171×28,
  agent-preset button (text "Standard mode", **no aria-label**) 144×28, composer row 28 px high.

## 2. Chat with messages + header/tabs + scroll (v1.3 evidence, v1.4 layout unchanged)

Screenshots: `02-chat.png`, `02b-chat-top.png`, `02c-chat-mid.png`, `02d-chat-bottom.png`,
`v14-10-keyboard.png` (keyboard variant).

- ✅ **No header/tab overlap** at scrollTop 0, mid and bottom: tab strip `[class*="wSkVaW_tabs"]`
  ends at y=84; real scroller is `[class*="wSkVaW_scrollBody"]` (y=84, h=640) and clips content
  cleanly. The "cut line" at the top is normal scroller clipping, not overlap.
- ✅ At max scroll the last message clears the composer: last `[class*="EvIC1a_column"]` bottom
  = 574 = composer (`[class*="composerSeat"]`) top.
- **P2** Composer floats over the message list with no fade/spacer; fine for chat UX, note only.
- **P2** (v1.3) long chat placeholder was truncated (`[class*="uV2eYG_placeholder"]`
  scrollW 382 / clientW 303); v1.4 claims the MutationObserver fix — re-verify here in v1.5.

## 3. Sidebar drawer (v1.4) — **P0**

Screenshot: `dist/audit/v14-03-sidebar.png`; measurements: `_tools/v14-sidebar.json`

- Drawer itself opens correctly: `[data-slot="sidebar"] > *` = 330×782, z=60, scrim opacity 1.
- **P0 — drawer footer (Account menu) is hidden behind the bottom nav.**
  `#dshp-nav` is `y=708..782` (74 px) with `z-index:70`; the drawer panel's footer button
  `button[aria-label="Account menu"]` is at `(10, 740, 310, 32)` → entirely inside the nav band.
  `document.elementFromPoint(account center)` returns `#dshp-nav button[data-dshp="new"]`, i.e.
  tapping there **starts a new session** instead of opening the account menu. Settings / Sign-in
  are therefore unreachable from the drawer (only via the native "More" menu).
  Fix: `[data-slot="sidebar"] > * { bottom: var(--dshp-nav-h) !important; height: auto !important;
  padding-bottom: 8px; }` (or add `padding-bottom: calc(var(--dshp-nav-h) + 8px)`), and/or hide
  the nav while a drawer is open.
- **P1** While the drawer is open the bottom nav remains interactive *above* the scrim
  (`#dshp-scrim` z=55 < `#dshp-nav` z=70): hitting the nav band either closes the drawer or fires
  New/More. Raise `#dshp-scrim` above the nav (e.g. z-index:75) and hide the nav when
  `html.dshp-drawer-open`.

## 4. Right sidebar / session panel (v1.4) — **P0**

Screenshots: `dist/audit/v14-04-rightbar.png` (tap result — panel stays hidden),
`04b-rightbar-forced.png` (panel force-rendered to show what should appear);
measurements `_tools/v14-rightbar.json`.

- Tapping `button[aria-label="Open right sidebar"]` **does** flip the DSH state: the panel gets
  `[data-slot="rightbar"] > .P3OORG_panel[data-sidebar-right-open="true"]`
  `data-sidebar-right-panel="fullscreen"` and inline `width:100vw`; the header button label
  becomes "Collapse right sidebar".
- But the skin never adds `html.dshp-rightbar-open`: `skin.js syncState()` derives drawer state
  from `[data-slot="root"] > div`'s inline `grid-template-columns`, while the skin CSS forces
  `grid-template-columns: 0px minmax(0,1fr) 0px !important` and DSH's rightbar no longer uses the
  right grid track (inline stays `… minmax(0px, 0px)`). So `[data-slot="rightbar"] > *` stays
  `display:none` → **the session panel (Workspace files / New terminal / Split) is unreachable.**
  Fix: in `syncState()`, also detect
  `document.querySelector('[data-slot="rightbar"] [data-sidebar-right-open="true"]')` (or the
  "Collapse right sidebar" button label) and toggle `html.dshp-rightbar-open`.
- When force-open (v1.3 proof) the panel is 361×782 at x=23 and needs the same treatment as a
  dialog: hide the bottom nav (`html.dshp-rightbar-open #dshp-nav { display:none }`) or raise the
  panel above it (nav z=70 > panel z=60), add bottom padding, and make the top icon row
  (`Split` / `Exit fullscreen` / `Collapse right sidebar`, each 28×28) ≥44×44.

## 5. Account menu popover (v1.3 evidence)

Screenshot: `05-account-menu.png` (opened via JS/native bridge); `05-account-menu-via-more.png`.

- Popover is `[role="menu"]` 220×140 with items `[role="menuitem"]` Settings / Feedback / Sign in,
  each 44 px high. ✅ tap targets fine.
- **P2** When opened from the native "More" path the popover is anchored to the *hidden* sidebar
  footer button, so it appears at the **top-left corner (12,12)** instead of near the "More"
  button. Fix: skin JS can detect a newly added `[role="menu"]` inside the account flow and
  position it fixed above the nav (`bottom: calc(var(--dshp-nav-h) + 8px); left: 12px;`) — or
  simply accept it as a sheet.

## 6. Sign in dialog (v1.4) — OK after v1.4 fix

Screenshot: `dist/audit/v14-06-signin.png`; data `_tools/v14-signin.json`.

- Dialog `[role="dialog"][aria-label="Waiting for sign in"]` = 336×265 at (24,258), fits viewport.
- ✅ v1.4 fixed the clipped primary button: "Continue in your browser" is now 123×46 and shows a
  loading spinner (the v1.3 "C" was that spinner being clipped).
- **P2** Close `button[aria-label="Close"]` is 28×46 (narrow, <44 px wide).
- "Copy sign-in link" 111×46 (underlined link) and "Cancel" 154×46 fine.
- **P2** Add-API-key dialog (v1.3 evidence, `06c-add-api-key.png`): `[role="dialog"]
  [aria-label="Add an API key to get started"]` 336×314; actions wrap to 2 lines
  ("Configure later" 128×46, "Save and continue" 152×46) and the "Authentication details"
  help button is 16×46. Suggest `white-space:nowrap` + flexible min-widths, and a visible ✕.

## 7. Settings dialog + tabs (v1.3 evidence — highest-risk area left to re-check in v1.5)

Screenshots: `07-settings-general.png`, `07b-settings-models.png`, `07c-settings-plugins.png`,
`07d-settings-presets.png`, zoom `_tools/crop-close-zoom.png`.

- **P1 — Close button collides with the tab strip and can scroll out of view.**
  `button.VOzbGW_close` (`[class*="VOzbGW_close"]`, 44×46, absolute top:6 right:6, z=5) sits on
  top of the "Built-in plugins" tab label (see zoom crop) because `[class*="VOzbGW_navList"]`
  occupies the same top band; and since the *panel* is the scroll container
  (`[class*="VOzbGW_panel"] { overflow: hidden auto }`) the ✕ scrolls with the content.
  Fix: make the header row sticky (`[class*="VOzbGW_header"] { position:sticky; top:0;
  background:panel; z-index:6 }`) and reserve space (`[class*="VOzbGW_navList"]{
  padding-right:56px }`), or pin the ✕ to the overlay.
- **P1 — Tab strip overflows:** `[class*="VOzbGW_navList"]` scrollWidth **542** vs clientWidth
  **384**; tabs are General 103 + Models 102 + Built-in plugins 150 + Agent presets 145 px.
  "Agent presets" starts at x=397 (off-screen initially) and "Built-in plugin…" is clipped; there
  is no scroll affordance. Fix: smaller font/padding to fit 4 tabs, or a 2×2 tab grid.
- **P1 — Two-column settings rows are cramped:** Permission/Language rows squeeze the
  description into ~120 px (5-line wrap) while the control sits on the right
  (`[class*="VOzbGW_options"]` rows; controls `.oY77xG_selector` / `.hVGvvW_selector` 46 px high).
  Fix for phones: stack label+description above a full-width control
  (`flex-direction: column; align-items: stretch`).
- **P2** Bottom content is clipped at the dialog edge (the "System" theme cube is cut at
  y=720/782); content does scroll (options container scrolls), but add bottom padding
  (e.g. 16 px) inside `[class*="VOzbGW_options"]`.
- **P2** Small targets: font-size arrows 17×46, expander 24×46, switches 36×46.

## 8. Model selector menu (v1.3 evidence) — good

Screenshot: `08-model-menu.png`; data: menu `[role="menu"]` 297×96 at (12,537) (opens upward),
items 289×44 ("Model …" / "Effort …"). ✅ fits viewport, tap targets fine.
**P2** cosmetic: the build tag chip ("1017") is still visible in the menu row
(`[role="menu"] [class*="_tag_"]`); the skin hides it only inside the composer pill.

## 9. Workspace selector + agent preset menus (v1.3 evidence) — good

Screenshots: `09-workspace-menu.png` (menu 220×102 at (28,196), items 44 px, checkmark on the
active workspace), `09b-preset-menu.png` (menu 328×303, scrolls, 4 preset rows with title +
description). ✅ Both fit; preset menu bottom (420) still fits the 432 px keyboard-open viewport.

## 10. Keyboard-open state (v1.4) — fixed

Screenshot: `dist/audit/v14-10-keyboard.png` (native screencap); measurements
`_tools/v14-scan-keyboard.json`.

- ✅ v1.4 fixed it: keyboard open → `html` gets `dshp-keyboard`, `#dshp-nav` `display:none`,
  composer bottom = 385 < viewport 432, hero content above. No overlap with the IME.
- **P2** While the keyboard is open the hero's top-right icon (right sidebar) is scrolled out of
  view; acceptable, but if the rightbar fix lands, keep the trigger reachable when editing.

## 11. Native "More" menu (Java `PopupMenu`) (v1.3 evidence — Java unchanged in v1.4)

Screenshot: `dist/audit/11-native-more-menu.png`; item bounds via `uiautomator dump`.

- **P0/P1 — the popup is anchored to the fullscreen WebView, so it renders off-screen at the
  bottom**: `Account, sign in & settings` bounds `[45,2266 576×8]` on a 2316 px screen (only an
  8 px sliver visible); the remaining 5 items are entirely below the screen edge.
  Fix (Java, `WebActivity.showMenu`): anchor the `PopupMenu` to a small dedicated anchor `View`
  placed above the bottom nav (e.g. 1×1 dp at bottom|end with `bottomMargin = dp(74+12)`), or
  replace it with an `AlertDialog`/`BottomSheetDialog` item list. Do not anchor to `webView`.

## 12. Tap-target inventory (<44 px) — repeated offenders

| element / selector | size |
| --- | --- |
| `.uV2eYG_add` `button[aria-label="Add files or run commands"]` | 28×28 |
| `button[aria-label^="Access mode"]` | 44×28 |
| `button[aria-label^="Select model"]` | 141×28 |
| hero `button[aria-label="Choose workspace"]` / agent-preset button | ×28 |
| `button[aria-label$="of context used"]`, turns/steps pill | 55×24 / 183×24 |
| `button[aria-label="Use actual duration"]`, "Collapse turns", "Collapse calls" | ×20 |
| `input[aria-label="Search trajectory"]` | 137×20 |
| Trajectory turn markers `button[aria-label^="Request #"]` | 16×16 |
| header `More actions`, `Open right sidebar` | 40×40 |
| rightbar `Split` / `Exit fullscreen` / `Collapse right sidebar` / `Choose shell` | 28×28 / 20×20 |
| settings font arrows / expander / switches | 17×46, 24×46, 36×46 |
| sign-in dialog Close | 28×46 |

Fix: min 44×44 hit area (padding or `min-height`), which mostly means raising the composer pill
row (currently 28 px despite the 38/40 px rules — the inner buttons are 28 px) and the
trajectory/filter row.

## 13. Trajectory tab (v1.3 evidence) — layout fine, toolbar too small

Screenshot: `02e-trajectory.png`; scan `_tools/scan-trajectory.json`.

- Layout works: filter row (Duration/Turns/Calls + search) over a timeline chart, then grouped
  entries. No horizontal page overflow, no content hidden behind nav. Rows intentionally
  ellipsize single-line (`[class*="Y0dWHa_contentText"]`, no expand affordance — consider
  tap-to-expand).
- **P1** all filter controls are 20 px high (see §12); **P2** request markers 16×16.

## 14. Dialog / menu DOM selector reference (for skin/Java work)

| UI | selector(s) |
| --- | --- |
| any modal | `[role="dialog"]`, `[role="alertdialog"]` (v1.4 also uses `html.dshp-dialog-open` to hide the nav) |
| Settings sheet | `[class*="VOzbGW_overlay"]` > `[class*="VOzbGW_panel"]`; header `[class*="VOzbGW_header"]`; tabs `[class*="VOzbGW_navList"]` / `[class*="VOzbGW_navCell"]`; close `[class*="VOzbGW_close"]`; content `[class*="VOzbGW_content"]`, `[class*="VOzbGW_options"]` |
| Sign-in | `[role="dialog"][aria-label="Waiting for sign in"]`, `…[aria-label="Could not sign in"]`; buttons: Close, "Copy sign-in link", Cancel, "Continue in your browser" |
| Add API key | `[role="dialog"][aria-label="Add an API key to get started"]`; buttons "Configure later", "Save and continue" |
| Account menu | `[role="menu"]` > `[role="menuitem"]` (Settings / Feedback / Sign in); trigger `button[aria-label="Account menu"]` (hidden in sidebar footer) |
| Model menu | `[role="menu"]`, items `[role="menuitem"]` (Model / Effort), tag `[class*="_tag_"]` |
| Workspace menu | `[role="menu"]`, items "Default workspace", "Add workspace…" |
| Preset menu | `[role="menu"]` with 4 items (Standard/PTC/Minimal/Creator) |
| Sidebar drawer | `[data-slot="sidebar"] > *`; state class `html.dshp-drawer-open`; toggle `button[aria-label="Open sidebar"/"Collapse sidebar"]` |
| Right sidebar | `[data-slot="rightbar"] > *`, state attribute `[data-sidebar-right-open="true"]`, `data-sidebar-right-panel="fullscreen"`; toggle `button[aria-label="Open right sidebar"/"Collapse right sidebar"]` |
| Bottom nav | `#dshp-nav button[data-dshp="chats|new|more"]`, height var `--dshp-nav-h` |
| Chat scroller | `[class*="wSkVaW_scrollBody"]`, header `[data-slot="conversation.header"] header`, tabs `[class*="wSkVaW_tabs"]` |
| Composer | `[data-slot="conversation.input.dock"]`, `textarea`/`[contenteditable]`, `[data-slot="conversation.input.*"]`, placeholder `[class*="uV2eYG_placeholder"]` |

## 15. Prioritized list

1. **P0** Right sidebar never opens (skin state detection) — §4.
2. **P0** Sidebar drawer footer/Account menu (and thus Settings/Sign-in) hidden under the nav — §3.
3. **P1** Native "More" popup menu renders off-screen at the bottom (Java anchor) — §11.
4. **P1** Settings: close ✕ collides with tabs / scrolls away; 4 tabs overflow (no affordance);
   two-column rows cramped — §7.
5. **P1** Composer pill row + trajectory toolbar + header icons under 44 px — §12.
6. **P2** Header right icon overflows viewport by 10 px — §1.
7. **P2** Account popover appears top-left instead of near trigger — §5.
8. **P2** Add-API-key action buttons wrap; no ✕ — §6.
9. **P2** Model menu shows build-tag chip; placeholder re-check in chat — §8, §2.
10. **P2** Sign-in Close 28 px wide; Trajectory turn markers 16 px — §6, §13.

## 16. Re-verify in v1.5 (not covered on v1.4 due to app restarts)

- Settings dialog after the v1.4 sheet rework (close ✕ reachable? tabs fit?), add-API-key dialog,
  model/workspace/preset menus, native More popup anchoring, Trajectory toolbar sizes, chat
  placeholder shortening, and the P0/P1 items above after the fixes.
