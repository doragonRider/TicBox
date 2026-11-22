# TicBox icon

Original SVG master: [ticbox-icon.svg](ticbox-icon.svg). A rugged round watch with ten-ten hands: a finely toothed bezel, a button at two o'clock, a crown with a red ring at four, and square lugs on a ridged strap. Its shape follows the outdoor watches the app works with, but the drawing is original and carries no maker's logo or name. The background is a light slate (`#D0DFE6`) that goes with the app's palette.

The source canvas and the Android foreground/monochrome VectorDrawables are 108 × 108 units. The watch head lies inside the centered 66-unit safe circle. Only the straps run past it, on purpose, so whichever mask the launcher uses cuts them at its edge. The background is full bleed and unmasked; Android applies the launcher mask and motion, so no rounded rectangle, shadow or border is added.

The monochrome layer keeps the silhouette with an open face, so a themed icon shows the hands on the wallpaper color. Its straps end at the case so they don't show through the face, and the crown's red ring becomes a gap.

Android packaging uses `mipmap-anydpi/ic_launcher.xml` with separate background, foreground and monochrome layers, and the same AdaptiveIconDrawable serves `roundIcon`. Min SDK is 34, so there is no legacy bitmap fallback. Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

The phone app and Local Bridge on the watch use the same layers. The status bar icon, `android/core/platform/src/main/res/drawable/ic_backend_watch.xml`, is the same watch redrawn at 24 dp without the bezel's teeth, which are too fine to show at that size.

`LauncherIconTest` checks that the three layers and the app name are packaged.
