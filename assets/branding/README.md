# TicBox icon

Original SVG master: [ticbox-icon.svg](ticbox-icon.svg). A round watch with a soft strap and ten-ten hands, in original sage coloring and geometry. The color artwork uses a soft glass gradient and round hand ends; the monochrome layer keeps the same clock silhouette.

The source canvas and the Android foreground/monochrome VectorDrawables are 108 × 108 units. All essential artwork lies inside the centered 66-unit safe circle. The background is full bleed and unmasked; Android applies the launcher mask and motion, so no rounded rectangle, shadow or border is added.

Android packaging uses `mipmap-anydpi/ic_launcher.xml` with separate background, foreground and monochrome layers, and the same AdaptiveIconDrawable serves `roundIcon`. Min SDK is 34, so there is no legacy bitmap fallback. Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

App resource tests render both vector layers and verify that every non-transparent pixel is inside the safe circle.
