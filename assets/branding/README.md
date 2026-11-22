# TicBox icon

SVG masters: [ticbox-icon.svg](ticbox-icon.svg) (warm white, the default), [ticbox-icon-navy.svg](ticbox-icon-navy.svg) and [ticbox-icon-mist.svg](ticbox-icon-mist.svg). A flat round watch with ten-ten hands: two-tone straps that widen toward the case, a round case with a thin inner ring, a small crown at three o'clock, minute dots, hour ticks, an orange marker at twelve and an orange second hand. The drawing is original, from a draft the maintainer supplied. Its shape follows the outdoor watches the app works with, but it carries no maker's logo or name.

In two of the colorways the straps, case and face take the background's hue (Material HCT tonal palettes), so the watch sits in its background instead of standing out from it as a black block:

| Colorway | Background | Watch |
|---|---|---|
| Warm white | `#F3EEE7` | caramel straps and case, dark brown face |
| Navy | `#1D3247` | steel blue straps and case, dark navy face |
| Mist | `#EAF0F5` | graphite straps and case, dark blue face |

The source canvas and the Android foreground and monochrome VectorDrawables are 108 × 108 units. The watch is drawn on a 512 unit square that a group fits into the 72 units a launcher shows, so all of it, strap ends included, lies inside the centered 66 unit safe circle. The background is full bleed and unmasked; Android applies the launcher mask and motion, so no rounded rectangle, shadow or border is added. VectorDrawable has no dashed strokes, so the minute dots are 60 small shapes. The straps' second tone is their right half drawn over the whole strap rather than a clip path, so no background shows through the seam.

The three colorways share one monochrome layer: the straps, case ring, crown, marker and hands, with the face left open, so a themed icon shows the hands on the wallpaper color.

The layers live in the `ui` module (`android/ui/src/main/res/drawable/ic_launcher_*.xml`, with the backgrounds in `values/launcher_icons.xml`) so that Appearance can show them, and the app's `mipmap-anydpi/ic_launcher*.xml` put them together. Each icon is a launcher entry of its own in the app manifest, all aliases of `CompanionActivity`: `.MainActivity` (warm white), `.LauncherIcon2` (navy) and `.LauncherIcon3` (mist). Appearance turns on one and the others off. The default keeps the name the Activity used to have, so icons already on a home screen survive an update. The names say nothing about the icons on purpose: an entry the user chose has to exist after an update too, or the app is left with no launcher entry at all, so a new drawing or colorway keeps the names and only changes the resources behind them. Min SDK is 34, so there is no legacy bitmap fallback. Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

A launcher icon can't be a picture chosen at run time, so the watch's current face goes on a pinned home screen shortcut instead (`AndroidFaceShortcut`), whose icon follows the face.

Local Bridge on the watch uses warm white. The status bar icon, `android/core/platform/src/main/res/drawable/ic_backend_watch.xml`, keeps the silhouette of the previous icon at 24 dp: tapered straps, a crown at three and a button at two, a little larger than to scale so they don't vanish at that size.

`LauncherIconTest` checks that the three icons are packaged with all three layers, and that choosing one turns on its launcher entry and the others off.
