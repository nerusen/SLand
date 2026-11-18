package io.github.odlaver.tepi

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

// What the island is painted with.
//
// The island used to be black and nothing else, which is the right default:
// black is what a notch is, and on an OLED panel it is the one colour that
// genuinely disappears into the bezel. But the shape is Tepi's, not Android's,
// and a user who wants it to be theirs has nowhere to say so. These are the
// palettes that say so.
//
// Two kinds, and they are not variations of one idea:
//
//   Monochrome   one dark tone and white on it. Amoled, Dark, Dark Gray, Gray.
//                The marks are white, the fills are white at low alpha, and
//                nothing else is coloured. This is the island as it was.
//
//   Combination  a coloured ground with a second colour on it. Royal Blue,
//                Violet, Midnight Blue, Crimson, Neon. White still does most
//                of the talking — a panel where every label is the accent is
//                a panel nobody can read — and the accent is for the quarter
//                of the surface that is meant to be looked at first: the
//                primary button, the page the user is on, the filled tile,
//                the logo, a progress bar's filled part. Black comes in on
//                top of the accent, wherever the accent is the ground.
//
// A combination theme may name up to three tones. The first is the ground, and
// the other two are the two steps above it that rows and cards are filled
// with; a theme that names only one gets translucent white for those, which is
// what the monochrome island always used. The accent is the last colour named,
// and it is lightened if it would not read against its own ground — Midnight
// Blue's three tones are all nearly black, so its accent has to be computed
// rather than taken as given, or the logo would be invisible on it.
class Theme(
    val key: String,
    val label: Int,
    // The island's own ground
    val back: Int,
    // Row and card fills. Zero means translucent white, as the dark themes use
    val tier: Int = 0,
    val lift: Int = 0,
    // Marks, the primary button, the current page. Zero means no accent at
    // all and the theme is monochrome
    accent: Int = 0,
) {
    // What is actually drawn with: the accent, lightened until it reads
    // against its own ground. A theme whose three tones are all nearly black
    // names its lightest tone as the accent, and taken literally that would
    // be a logo the same colour as the island.
    val mark = if (accent == 0) 0 else legible(accent, back)

    val coloured get() = mark != 0

    // What fills a surface that is meant to be pressed or is the one in use:
    // the accent where there is one, white where there is not
    val lead = if (coloured) mark else WHITE

    // And what is written on top of that surface.
    //
    // Whichever of black and white actually reads against it, measured rather
    // than guessed from a luminance threshold. A threshold gets the easy cases
    // right and the interesting ones wrong: Violet's accent sits just under
    // any sensible cut-off, so a threshold picks white for it — and white on
    // that violet is a contrast ratio under four, which is a label you have to
    // lean in to read. Black on the same violet is over five. The two are
    // compared and the better one wins, which cannot be wrong for any accent
    // a user might pick.
    val onLead = if (ratio(Color.BLACK, lead) >= ratio(WHITE, lead)) Color.BLACK else WHITE

    companion object {
        private val WHITE = Color.rgb(245, 245, 247)

        // Relative luminance ratio, the WCAG one: how far apart two colours
        // are to the eye rather than in the cube
        private fun ratio(a: Int, b: Int): Float {
            val la = Color.luminance(a) + 0.05f
            val lb = Color.luminance(b) + 0.05f
            return max(la, lb) / min(la, lb)
        }

        // Walk a colour towards white until it stands off its ground. Towards
        // white rather than towards its own hue at full saturation: the point
        // is to keep the colour the user picked recognisable, and a lightened
        // navy still reads as navy where a saturated one reads as a different
        // theme.
        private fun legible(c: Int, on: Int): Int {
            if (ratio(c, on) >= MIN_RATIO) return c
            var best = c
            var k = 0.1f
            while (k <= 0.9f) {
                best = Color.rgb(
                    (Color.red(c) + (255 - Color.red(c)) * k).toInt(),
                    (Color.green(c) + (255 - Color.green(c)) * k).toInt(),
                    (Color.blue(c) + (255 - Color.blue(c)) * k).toInt(),
                )
                if (ratio(best, on) >= MIN_RATIO) return best
                k += 0.1f
            }
            return best
        }

        // Enough for a mark or a short label, not enough for body text. Body
        // text stays white in every theme, which is why this is not 4.5.
        private const val MIN_RATIO = 3.2f

        const val KEY = "island.theme"

        // Four greys and five combinations. The greys are first because the
        // first of them is the default and the island has always been it.
        val all = listOf(
            Theme("amoled", R.string.th_amoled, Color.rgb(0x00, 0x00, 0x00)),
            Theme("dark", R.string.th_dark, Color.rgb(0x0A, 0x0A, 0x0A)),
            Theme("darkgray", R.string.th_dark_gray, Color.rgb(0x17, 0x17, 0x17)),
            Theme("gray", R.string.th_gray, Color.rgb(0x26, 0x26, 0x26)),
            Theme("royal", R.string.th_royal, Color.rgb(0x00, 0x55, 0xFF), accent = Color.rgb(0xB5, 0xF8, 0x00)),
            Theme(
                "violet", R.string.th_violet, Color.rgb(0x0F, 0x03, 0x2D),
                tier = Color.rgb(0x4E, 0x2B, 0xCC), lift = Color.rgb(0x90, 0x5B, 0xF4),
                accent = Color.rgb(0x90, 0x5B, 0xF4),
            ),
            Theme(
                "midnight", R.string.th_midnight, Color.rgb(0x00, 0x07, 0x2D),
                tier = Color.rgb(0x05, 0x16, 0x50), lift = Color.rgb(0x0A, 0x24, 0x72),
                accent = Color.rgb(0x0A, 0x24, 0x72),
            ),
            Theme("crimson", R.string.th_crimson, Color.rgb(0xB4, 0x00, 0x23), accent = Color.rgb(0xFF, 0xCF, 0x0D)),
            Theme("neon", R.string.th_neon, Color.rgb(0x03, 0x0F, 0x0F), accent = Color.rgb(0x00, 0xDF, 0x82)),
        )

        fun of(prefs: Prefs): Theme {
            val want = prefs.str(KEY, "amoled")
            return all.firstOrNull { it.key == want } ?: all[0]
        }
    }
}
