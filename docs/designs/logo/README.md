# Logo concepts

Chosen: **a-ring-check** (progress ring with a check, indigo `#6c86f5` on `#0d0f14`). It is the only concept that stays legible at 16px and says "habit done" without text. The old icon was a blurry teal raster (wrong accent, tiny source upscaled, no SVG).

Alternates: `b-streak-grid` (diagonal streak in a 3x3 day grid), `c-grid-h` (H built from grid cells). Each has `<name>.svg` (rounded, "any") and `<name>.maskable.svg` (full-bleed, mark inside the central 80% circle). `contact-sheet.png` compares all three at 16/32/192/512 on dark and light, plus a circular-mask preview.

To swap: render the chosen SVGs to `habitTracker/src/main/resources/static/` as `android-chrome-192x192.png`, `android-chrome-512x512.png` (any), `android-chrome-maskable-512x512.png` (maskable), `apple-touch-icon.png` (180, maskable variant), `favicon-32x32.png`, `favicon-16x16.png`, `favicon.ico` (any variant, mark scaled 1.25x for small sizes), and copy the 1.25x "any" SVG to `static/favicon.svg`. Then bump `VERSION` in `static/sw.js`. The PNGs were rendered with cairosvg in a throwaway venv.
