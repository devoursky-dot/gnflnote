# GNFLNOTE V2.0 — Architecture, Reverse-Engineering Map & AI Handoff Guide

> **Purpose**: This document records the complete project history, build pipeline, stock EasiNote internal class/field map, and GitHub OTA hot-update workflow so **any AI agent or developer** can safely maintain, extend, or release updates for `GNFLNOTE` (`com.seewo.easignfl`) without re-discovering internal details.

---

## 1. Project Identity & Environment
- **Target Device**: 4K (`3840×2160`) Android interactive classroom whiteboard (Gyeongnam Foreign Language High School / 경남외국어고등학교)
- **Package Name**: `com.seewo.easignfl` (repackaged & extended Seewo EasiNote)
- **Active Workspace**: `C:\Work\GNFLNOTE-V2.0` → Output APK `C:\Work\GNFLNOTE-V2.0.apk`
- **Version**: `versionCode = 20900200`, `versionName = "2.0"`
- **GitHub Repository**: `https://github.com/devoursky-dot/gnflnote` (`main` branch)
- **Strict Version Policy**: Never modify finalized previous version folders (`GNFLNOTE`, `GNFLNOTE-V1.1` ~ `GNFLNOTE-V1.7`). Always bump into a new versioned folder for major APK releases, or release lightweight OTA patches via `ota/gnfl_patch.dex`.

---

## 2. Two-Tier Release & GitHub OTA Update Architecture (V2.0)

The full APK is **~280 MB** (99.9% stock EasiNote native libraries and assets in `app_root/`), whereas all custom Java code (`kr.hs.gnflhs.whiteboard.*`) compiles to **~193 KB** (`ota/gnfl_patch.dex`).

### How OTA Hot-Update Works (`UpdateBootstrap.java`)
1. **Static DEX Hooks (`scripts/patch_dex.py`)**:
   - `PlainWhiteboardActivity.onActivityResult` → `UpdateBootstrap.onActivityResult(...)`
   - `PlainWhiteboardActivity.dispatchTouchEvent` → `UpdateBootstrap.dispatchTouchEvent(...)`
   - `PopupX` `BadTokenException` NOP bypass
   - Max Zoom clamp patched from `300%` (`3.0f`) → `500%` (`5.0f`)
2. **Startup Loading (`ChildFirstDexClassLoader`)**:
   - On launch, `UpdateBootstrap` checks `context.getFilesDir()/gnfl_ota/active_patch.json`.
   - If a verified local `gnfl_patch_<ver>.dex` exists with `patchVersion > BUILTIN_PATCH_VERSION` (`200`) and `minBaseVersionCode <= BASE_VERSION_CODE` (`20900200`):
     - Verifies SHA-256 hash, sets `dexFile.setReadOnly()` (Android 14+ security requirement).
     - Loads `kr.hs.gnflhs.whiteboard.*` (except `UpdateBootstrap`) **child-first** from `gnfl_patch_<ver>.dex`, overriding the built-in copy in the base APK!
     - On any error, seamlessly falls back to the built-in classes inside the base APK (zero crash risk).
3. **Background GitHub Update Check**:
   - 2.5 seconds after launch, fetches `https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/ota/version.json`.
   - If `remote.patchVersion > activePatchVersion`, downloads `ota/gnfl_patch.dex` (~193 KB), verifies SHA-256, promotes atomically, marks `justUpdated = true`, and displays a top-center banner: `⬇️ 새 업데이트(vX.Y.Z) 다운로드 완료! 앱을 다시 열면 자동 적용됩니다.`
   - Next app launch automatically loads the new patch and displays `✨ GNFLNOTE vX.Y.Z 자동 업데이트가 적용되었습니다!`.

### How to Release a New Lightweight OTA Update (~193 KB, No APK Reinstall!)
1. Edit any Java file under `src/kr/hs/gnflhs/whiteboard/` (except `UpdateBootstrap.java`).
2. Run:
   ```powershell
   cd C:\Work\GNFLNOTE-V2.0
   .\scripts\build_ota.ps1 -PatchVersion 202 -PatchName "2.0.2" -Notes "Describe change"
   ```
3. Commit and push `src/`, `ota/gnfl_patch.dex`, and `ota/version.json` to GitHub `main`:
   ```powershell
   git add src/ ota/
   git commit -m "OTA v2.0.2: Describe change"
   git push origin main
   ```
4. Every classroom whiteboard with V2.0 installed will automatically download and apply v2.0.2 on next launch!

### When a Full APK Rebuild (`.\build.ps1`) Is Needed
Only when changing:
- `UpdateBootstrap.java` itself
- App icon (`app_root/res/drawable-*-v4/app_easinote_launcher.webp`)
- `AndroidManifest.xml` permissions/version (`scripts/patch_version.py`)
- Stock DEX hooks (`scripts/patch_dex.py`) or third-party JARs (`libs/zxing-core.jar` → `classes3.dex`)

---

## 3. Version History Summary (V1.0 → V2.0)
- **V1.0–V1.4**: Base repackaging, SmartPen auto-straightening (line/circle), 4K PDF picker + vector Lasso capture + dominant background color matching, ink-only clear (`삭제`), width/page fit (`폭맞춤`/`쪽맞춤`), 500% max zoom, contrast quick color palette.
- **V1.5**: Binary AXML version patcher (`patch_version.py`), main menu cleanup (`앱 나가기` with `icon_exit`, hidden unused rows), 3 GB LRU PDF cache (`PdfCacheManager`) + side-expanding recent PDF submenu (`PdfRecentSubmenu`) with per-PDF last-page memory, ruler hidden.
- **V1.6**: Corner-based Triangle/Rectangle recognition with `±10°` baseline snap & independent-edge razor-sharp replay (`InkShapeRecognizer`, `SmartPenManager`), always-next-page PDF insertion, 7-color/14-shade quick palette (`QuickColorBarManager`).
- **V1.7**:
  - PDF embedded `/URI` hyperlink + 4K vector QR code tap-to-open dialog when `선택` tool is active (`PdfLinkQrManager` + ZXing `classes3.dex`).
  - Independent A/B pen (`가는펜`/`굵은펜`) 3× seamless native `ToolType.HIGHLIGHTER` (0% seams via post-stroke `forceRepaintWhiteboard`, true 1× SeekBar position without jumping to max, direct `d4.p` callback without `ToolbarView.O` tab-jump bug).
  - Per-file 4-digit PIN lock/unlock (SHA-256 hashed, compact privacy keypad, single-active-file session unlock that auto-relocks on switching PDF or exiting app), optional `☐ 캐시에 저장` checkbox (unchecked by default = one-time use), long-press action dialog (`PIN 잠금 설정` / `캐시에서 삭제`) for unlocked cached PDFs.
  - Top-center touch-through pill banner (`ToolbarFitHelper.showTopToast`) replacing bottom toasts.
  - Pen-style thickness SeekBar + live circular preview dot inside Shape popup (`DietManager`), supporting all 2D (`IStroke.setStrokeWidth`), 3D (`AbstractPolygon.setStroke`), and 1st Hand Smart-Shape (async delayed check with `ACTION_DOWN` cancel guard).
  - Custom GNFLHS inner-book vector app launcher icon (`학교로고.svg`).
- **V2.0**:
  - Fullscreen `도형` toolbar button visibility guard (`DietManager.ensureShapeButtonVisibleIfWide`) so `도형` never collapses into `더 보기` on 4K fullscreen while preserving `더 보기` in narrow freeform windows.
  - GitHub Ultra-Lightweight OTA DEX Hot-Update system (`UpdateBootstrap.java`, `scripts/build_ota.ps1`, `ota/version.json`, `ota/gnfl_patch.dex`).

---

## 4. Stock EasiNote Internal Class & Reflection Map (Verified from Bytecode)

| Stock Class / Field | Role | Important Notes |
|---|---|---|
| `PlainWhiteboardActivity.e` | `ToolbarView` | Holds `getPen()`, `getGeometry()`, `e` (`d4` PenWindow), `f` (`m4` ShapeWindow). |
| `PlainWhiteboardActivity.t` | `p4.a` | `t.a()` returns `stateHolder`; `stateHolder.m0()` returns current `Page` (`size()`, `get(i)`). |
| `stateHolder.R(Rect, boolean)` | Canvas repaint | Pass `(null, true)` to repaint vector layer cleanly **without** resetting gestures. |
| `stateHolder.m()` | Presenter reset | Resets active gesture/presenter — **never** call mid-drag! |
| `com.seewo.easinote.n4.g.b()` | Dual-pen store | `c(p)` color, `d(p)` width, `e(p)` type, `h(p,c)`, `i(p,w)`, `j(p,type)`. `p`: `0=finger, 1=pen_one(thin), 2=pen_two(thick)`. |
| `ToolbarView.O(ToolType,int,float)` | **DANGER** | Calls `d4.g1(...)` + `d4.s1()` → `d4.l1(2)` which **forcibly jumps active tab to `pen_two` (굵은펜)** and slams SeekBar! Avoid calling it; update `d4.p.e(ToolType)` + `d4.p.l(tab)` directly instead. |
| `com.seewo.easinote.toolbar.d4` | Pen popup | `d4.p` (`i4.e.a`) is the drawing engine callback; `d4.F` is active pen tab index. |
| `com.seewo.easinote.toolbar.m4` | Shape popup | `m4.u` is `Geometry`; layout `explore_edu_shape_window.xml` (`0x7f0801ee` left 2D/3D RadioGroup, `0x7f0801c6` plane grid, `0x7f080235` solid grid, `0x7f0800a2` color grid). |
| `com.seewo.libshapes.interfaces.IStroke` | 2D shapes | `getStrokeWidth()` / `setStrokeWidth(float)` |
| `com.seewo.libshapes.AbstractPolygon` | 3D shapes | Superclass of `Abstract3dShape` (`Cube`, `Cylinder`, `Cone`, `Pyramid`, `Sphere`). Uses **`getStroke()` / `setStroke(float)`** (NOT `IStroke`!). |
| Toolbar Button IDs | View IDs | `explore_edu_toolbar_choice` (`선택`), `explore_edu_toolbar_write` (`쓰기`), `explore_edu_toolbar_eraser` (`지우개`), `explore_edu_toolbar_clean` (`삭제`), `explore_edu_toolbar_shape` (`도형`), `explore_edu_toolbar_move` (`이동`), `explore_edu_toolbar_list` (`목록`), `explore_edu_toolbar_exit` (`나가기`). |

---

## 5. Source File Inventory (`src/kr/hs/gnflhs/whiteboard/`)
- `UpdateBootstrap.java` — V2.0 OTA Child-First DEX loader & GitHub background updater
- `SmartPenManager.java` — Global `dispatchTouchEvent` router, SmartPen shape recognition & replay, Highlighter seam repaint, Shape width tracker
- `InkShapeRecognizer.java` / `SmartPenOverlayView.java` — Corner-based line/circle/triangle/rectangle geometry recognizer & preview overlay
- `DietManager.java` — Menu/popup customization, 4th Highlighter slot, Shape popup thickness SeekBar, fullscreen `도형` visibility guard, `앱 나가기` & `PDF 불러오기` hooks
- `QuickColorBarManager.java` — 7-color (14-shade) background-adaptive quick palette & per-tab A/B pen Highlighter state
- `PdfPicker.java` — SAF PDF opener, FHD/QHD/4K page picker, `☐ 캐시에 저장` checkbox, always-next-page insertion
- `PdfCacheManager.java` — 3 GB LRU internal PDF cache, per-PDF last-page memory, SHA-256 per-file 4-digit PIN lock & single-file session unlock
- `PdfRecentSubmenu.java` — Side-expanding recent PDF list, compact 4-digit PIN keypad, long-press lock/delete dialog
- `PdfLinkQrManager.java` — Embedded PDF `/URI` annotation parser + 4K vector QR code scanner (ZXing)
- `LassoCaptureManager.java` / `LassoOverlayView.java` — 4K vector re-rendered lasso cutout to new page
- `ToolbarFitHelper.java` — Ink-only clear, width/page fit, `forceRepaintWhiteboard`, top-center touch-through pill toast (`showTopToast`)
