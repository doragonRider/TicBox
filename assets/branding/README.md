# TicBox icon

Original SVG masters: [ticbox-icon.svg](ticbox-icon.svg) (dark dial, the default), [ticbox-icon-slate.svg](ticbox-icon-slate.svg) and [ticbox-icon-white-dial.svg](ticbox-icon-white-dial.svg). A flat round watch with ten-ten hands: tapered straps, square shoulders, a small rounded crown at three o'clock, a slim button at two and one amber marker at twelve, in solid colors from the app's slate palette. Its shape follows the outdoor watches the app works with, but the drawing is original and carries no maker's logo or name.

The source canvas and the Android foreground/monochrome VectorDrawables are 108 × 108 units. The watch head lies inside the centered 66-unit safe circle. Only the straps run past it, on purpose, so whichever mask the launcher uses cuts them at its edge. The background is full bleed and unmasked; Android applies the launcher mask and motion, so no rounded rectangle, shadow or border is added.

The three colorways share one monochrome layer. It keeps the silhouette with an open face, so a themed icon shows the hands on the wallpaper color.

The layers live in the `ui` module (`android/ui/src/main/res/drawable/ic_launcher_*.xml`, with the backgrounds in `values/launcher_icons.xml`) so that Appearance can show them, and the app's `mipmap-anydpi/ic_launcher*.xml` put them together. Each icon is a launcher entry of its own in the app manifest: `.MainActivity`, `.IconSlate` and `.IconWhiteDial`, all aliases of `CompanionActivity`. Appearance turns on one and the others off. The default keeps the name the Activity used to have, so icons already on a home screen survive the update. Min SDK is 34, so there is no legacy bitmap fallback. Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

A launcher icon can't be a picture chosen at run time, so the watch's current face goes on a pinned home screen shortcut instead (`AndroidFaceShortcut`), whose icon follows the face.

Local Bridge on the watch uses the dark dial. The status bar icon, `android/core/platform/src/main/res/drawable/ic_backend_watch.xml`, is the same watch's silhouette at 24 dp. Its crown and button are a little larger than to scale, or they would vanish at that size.

`LauncherIconTest` checks that the three icons are packaged with all three layers, and that choosing one turns on its launcher entry and the others off.
