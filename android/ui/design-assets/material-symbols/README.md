# Material Symbols

The app's icons are Google [Material Symbols](https://fonts.google.com/icons) (Outlined, 24px),
imported from [google/material-design-icons](https://github.com/google/material-design-icons) at
the revision pinned in `icons.json`.

To add an icon, append it to `icons.json` (`"filled": true` also imports the filled variant,
`"mirrored": true` flips it in right-to-left layouts, `"classic": "<category>"` takes the older
Material Icons drawing from the same repository, used where the symbol is shorter or thinner), run
`node tools/import-material-symbols.mjs`, then reference it from `AppIcons`.

The script writes `ic_<name>.xml` (and `ic_<name>_filled.xml`) vector drawables with the original
path data, and records each source URL in `manifest.json` (the pinned revision fixes the content). `LICENSE` is the upstream
Apache 2.0 license; it is also packaged as `res/raw/material_symbols_license.txt` with
`material_symbols_notice.txt`.
