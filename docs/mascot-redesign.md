# Animated robot mascot

The robot keeps its rounded cream casing and teal ears. Its antenna now stands
upright, and the face is drawn separately from the stationary head artwork.
The Dashboard contains one mascot; the duplicate sidebar mascot was removed.

- Reading: pupils scan left to right and return to the next line, with blinks.
  Scanning, buying, reading orders and auditing slots use this choreography.
- Sales: two dollar-sign reels scroll continuously, decelerate, and stop at
  different times. A second sale restarts them, even during the celebration.
- Idle: half-lidded eyes glance sideways, roll upward, blink and yawn on an
  eleven-second cycle. A stopped session uses this bored idle too.
- Working: the eyes track and the three mouth dots pulse independently.
- Paused, sleeping and alert states have their own facial motion.

MascotAnimation samples continuous poses by elapsed time. MascotFace draws
pupils, eyelids, brows, mouths and clipped dollar reels. State changes interpolate
the facial geometry instead of cross-fading complete images. Reduced motion
uses fixed representative poses. The head no longer hovers or rocks.

The Dashboard's **Preview HUD** cycles through reading, sales, bored idle and
working animations using explicitly labeled sample data for thirty seconds.

## Assets and preview

The active asset is
`trader-fabric/src/main/resources/assets/doughbay/textures/gui/mascot_shell.png`.
It is a transparent 1254 × 1254 PNG generated with the built-in image tool,
using the approved robot atlas as a reference. The old expression atlas is no
longer rendered.

`docs/assets/mascot-animation.gif` demonstrates the actual production facial
drawing code at presentation and HUD sizes, rendered offline with Java2D.

## Shell generation prompt

Use case: precise-object-edit. Input is the APPROVED robot expression atlas; use the FIRST top-left robot as identity reference and edit target. Produce ONE single head sprite (not a sheet), square PNG, genuinely transparent background. Keep EXACT same cute warm cream rounded casing, dark navy outline, teal side ear modules, two-tone cartoon shading, proportions and front-on view. Change ONLY: (1) remove ALL eyes, eyebrows, mouth, glints, symbols from inside the dark face display, leaving a smooth completely EMPTY flat dark navy screen (#101c25), ready for eyes to be animated in code; (2) straighten antenna: stem stands VERTICALLY UP from exact CENTER of head and its rounded square teal tip is level and UPRIGHT, not tilted or leaning. Antenna compact, about 22% of total head height. Do not redesign casing. Head only, no body, arms or feet. Center sprite, same wide soft squircle head and teal ears. Fit everything inside canvas with ~5% transparent margin all around, head including antenna occupying ~90% height. Clean dark outer edges, zero white matte or halos. No eyes, no face, no text, no grid, no background, no shadow outside head. Keep the empty screen perfectly unobstructed with large area for animated eyes. Single isolated production head shell asset.
