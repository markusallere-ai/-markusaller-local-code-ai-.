package ai

/** Generates fixed standalone examples that do not depend on extracted prompt fields. */
internal class ExampleSnippetGenerator {
    /** Chooses ascending or descending order from the request. */
    fun generateSortingFunction(prompt: String): String {
        val normalized = normalizePrompt(prompt)
        val descendingTerms = listOf("absteigend", "absteigende", "descending", "groessten zuerst")
        val sortingCall = if (containsAny(normalized, descendingTerms)) {
            "numbers.sortedDescending()"
        } else {
            "numbers.sorted()"
        }
        return "fun sortiereListeZahlen(numbers: List<Int>): List<Int> {\n" +
            "    return $sortingCall\n" +
            "}\n\n" +
            "fun main() {\n" +
            "    val sorted = sortiereListeZahlen(listOf(5, 2, 8, 1))\n" +
            "    println(sorted)\n" +
            "}\n"
    }

    /** Generates a circle-circumference function instead of a pi-digit approximation. */
    fun generateCircumferenceFunction(): String =
        "import kotlin.math.PI\n\n" +
            "fun berechneKreisumfang(radius: Double): Double {\n" +
            "    require(radius >= 0.0) { \"Der Radius darf nicht negativ sein\" }\n" +
            "    return 2 * PI * radius\n" +
            "}\n"

    /** Generates a memoized Fibonacci example that reuses recursive results. */
    fun generateFibonacciFunction(): String =
        "// Memoisierte Fibonacci-Berechnung (empfohlen bis n <= 45)\n" +
            "fun fibonacci(n: Int, cache: MutableMap<Int, Int> = mutableMapOf()): Int {\n" +
            "    require(n >= 0) { \"n muss >= 0 sein\" }\n" +
            "    cache[n]?.let { return it }\n" +
            "    val value = if (n <= 1) n else fibonacci(n - 1, cache) + fibonacci(n - 2, cache)\n" +
            "    cache[n] = value\n" +
            "    return value\n" +
            "}\n\n" +
            "fun main() {\n" +
            "    println(fibonacci(10))\n" +
            "}\n"

    /** Bounds requested precision and generates a Machin-formula pi calculation. */
    fun generatePiFunction(prompt: String): String {
        val precision = TextPatterns.PI_PRECISION.find(prompt)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceIn(MIN_PI_PRECISION, MAX_PI_PRECISION)
            ?: DEFAULT_PI_PRECISION
        val previewLength = (precision - PI_PREVIEW_PADDING).coerceAtLeast(MIN_PREVIEW_LENGTH)
        return "import java.math.BigDecimal\n" +
            "import java.math.MathContext\n" +
            "import java.math.RoundingMode\n\n" +
            "fun arctan(x: BigDecimal, mc: MathContext): BigDecimal {\n" +
            "    var result = BigDecimal.ZERO\n" +
            "    var term = BigDecimal.ONE.divide(x, mc)\n" +
            "    var xPower = term\n" +
            "    var n = 1\n" +
            "    var sign = BigDecimal.ONE\n" +
            "    while (term.abs() > BigDecimal.ONE.scaleByPowerOfTen(-mc.precision)) {\n" +
            "        result = result.add(sign.multiply(term), mc)\n" +
            "        n += 2\n" +
            "        xPower = xPower.divide(x.multiply(x), mc)\n" +
            "        term = xPower.divide(BigDecimal(n), mc)\n" +
            "        sign = sign.negate()\n" +
            "    }\n" +
            "    return result\n" +
            "}\n\n" +
            "fun calculatePi(precision: Int = $precision): BigDecimal {\n" +
            "    val mc = MathContext(precision, RoundingMode.HALF_UP)\n" +
            "    val arctan1_5 = arctan(BigDecimal(5), mc)\n" +
            "    val arctan1_239 = arctan(BigDecimal(239), mc)\n" +
            "    return BigDecimal(4).multiply(BigDecimal(4).multiply(arctan1_5).subtract(arctan1_239), mc)\n" +
            "}\n\n" +
            "fun main() {\n" +
            "    val pi = calculatePi($precision)\n" +
            "    val plain = pi.toPlainString()\n" +
            "    println(plain.take(${previewLength.coerceAtMost(MAX_PREVIEW_LENGTH)}))\n" +
            "}\n"
    }

    private fun normalizePrompt(prompt: String): String = TextFold.fold(prompt)
        .replace(TextPatterns.PUNCTUATION, " ")
        .replace(TextPatterns.WHITESPACE, " ")
        .trim()

    private fun containsAny(prompt: String, keywords: List<String>): Boolean =
        TextPatterns.containsWord(prompt, keywords)

    private companion object {
        const val MIN_PI_PRECISION = 10
        const val MAX_PI_PRECISION = 500
        const val DEFAULT_PI_PRECISION = 120
        const val PI_PREVIEW_PADDING = 18
        const val MIN_PREVIEW_LENGTH = 10
        const val MAX_PREVIEW_LENGTH = 500
    }
}
