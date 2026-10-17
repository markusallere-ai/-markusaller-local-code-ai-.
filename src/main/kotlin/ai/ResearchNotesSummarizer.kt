package ai

/** Builds a reviewable extractive draft from the first concise sentence of each saved note. */
internal object ResearchNotesSummarizer {
    private const val MAX_SENTENCES = 6
    private const val MAX_WORDS = 80
    private const val MIN_SENTENCE_LETTERS = 8
    private val timestampLine = Regex("(?m)^----- .* -----\\s*$")
    private val headingLine = Regex("^(?:#{1,6}\\s+.+|.{1,48}:)$")
    private val bulletPrefix = Regex("^(?:[•*-]\\s*|\\d+[.)]\\s*)")

    fun suggest(notes: String): String {
        if (notes.isBlank()) return ""

        val candidates = timestampLine.split(notes)
            .mapNotNull(::firstSentenceFromNote)
            .distinctBy { TextFold.fold(it) }
        val selected = mutableListOf<String>()
        var wordCount = 0
        for (sentence in candidates) {
            val sentenceWords = sentence.split(Regex("\\s+")).size
            if (selected.size == MAX_SENTENCES || wordCount + sentenceWords > MAX_WORDS) break
            selected += sentence
            wordCount += sentenceWords
        }
        return selected.joinToString(System.lineSeparator())
    }

    private fun firstSentenceFromNote(note: String): String? {
        val text = note.lineSequence()
            .map { bulletPrefix.replace(it.trim(), "").trim() }
            .filter { it.isNotEmpty() && !headingLine.matches(it) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return null

        return text.split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .firstOrNull { sentence ->
                sentence.count(Char::isLetter) >= MIN_SENTENCE_LETTERS &&
                    sentence.split(Regex("\\s+")).size <= MAX_WORDS
            }
    }
}

internal object ResearchNotesRequest {
    private val phrases = listOf(
        "geh die infos durch",
        "geh die informationen durch",
        "geh die notizen durch",
        "infos durchgehen",
        "fasse die recherche infos zusammen",
        "speichere die infos als wissen"
    )

    fun matches(prompt: String): Boolean {
        val normalized = TextFold.fold(prompt)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return phrases.any(normalized::contains)
    }
}
