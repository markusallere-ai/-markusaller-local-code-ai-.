package ai

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisiert Text einheitlich, damit deutsche Eingaben unabhängig von
 * Großschreibung und Umlautschreibweise vergleichbar sind.
 */
internal object TextFold {
    /** Verwendet Unicode-NFKC, Kleinschreibung und ASCII-Umschreibungen für deutsche Sonderzeichen. */
    fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")
}
