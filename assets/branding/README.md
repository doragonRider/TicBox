# TicBox icon

Original SVG master: [ticbox-icon.svg](ticbox-icon.svg). A round outdoor watch with ten-ten hands: a silver bezel with an engraved herringbone, a thin dark case with square shoulders, a small rounded crown at three o'clock and a slim button at two, and straps that taper away as they curve round the wrist. Its shape follows the outdoor watches the app works with, but the drawing is original and carries no maker's logo or name. The colors come from the app's slate palette, on a light slate background (`#D0DFE6`).

The source canvas and the Android foreground/monochrome VectorDrawables are 108 × 108 units. The watch head lies inside the centered 66-unit safe circle. Only the straps run past it, on purpose, so whichever mask the launcher uses cuts them at its edge. The background is full bleed and unmasked; Android applies the launcher mask and motion, so no rounded rectangle, shadow or border is added.

The monochrome layer keeps the silhouette with an open face, so a themed icon shows the hands on the wallpaper color. Its straps end under the shoulders so they don't show through the face, and the engraving is left out.

Android packaging uses `mipmap-anydpi/ic_launcher.xml` with separate background, foreground and monochrome layers, and the same AdaptiveIconDrawable serves `roundIcon`. Min SDK is 34, so there is no legacy bitmap fallback. Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

The phone app and Local Bridge on the watch use the same layers. The status bar icon, `android/core/platform/src/main/res/drawable/ic_backend_watch.xml`, is the same watch redrawn at 24 dp without the engraving. Its crown and button are a little larger than to scale, or they would vanish at that size.

`LauncherIconTest` checks that the three layers and the app name are packaged.
