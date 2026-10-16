package ai

import kotlin.test.Test
import kotlin.test.assertTrue

/** Verifies boundaries and essential behavior of standalone example snippets. */
class ExampleSnippetGeneratorTest {
    private val generator = CodeGenerator(BpeTokenizer())

    @Test
    fun piExampleUsesDocumentedDefaultPrecisionWhenNoneIsRequested() {
        val generated = generator.generate("Berechne pi", Intent.Unknown)

        assertTrue(generated.contains("fun calculatePi(precision: Int = 120)"), generated)
        assertTrue(generated.contains("calculatePi(120)"), generated)
    }

    @Test
    fun piExampleUsesRequestedPrecisionWithinSupportedRange() {
        val generated = generator.generate("Berechne pi mit 40 Stellen", Intent.Unknown)

        assertTrue(generated.contains("fun calculatePi(precision: Int = 40)"), generated)
        assertTrue(generated.contains("calculatePi(40)"), generated)
    }

    @Test
    fun piExampleClampsRequestedPrecisionToMinimumAndMaximum() {
        val tooLow = generator.generate("Berechne pi mit 1 Stelle", Intent.Unknown)
        val tooHigh = generator.generate("Berechne pi mit 9999 Stellen", Intent.Unknown)

        assertTrue(tooLow.contains("fun calculatePi(precision: Int = 10)"), tooLow)
        assertTrue(tooHigh.contains("fun calculatePi(precision: Int = 500)"), tooHigh)
        assertTrue(tooHigh.contains("plain.take(482)"), tooHigh)
    }

    @Test
    fun piImportsPrecedeTopLevelFunctionsAndUseMachinFormulaTerms() {
        val generated = generator.generate("Berechne pi", Intent.Unknown)

        assertTrue(generated.indexOf("import java.math.BigDecimal") < generated.indexOf("fun arctan"), generated)
        assertTrue(generated.contains("arctan(BigDecimal(5), mc)"), generated)
        assertTrue(generated.contains("arctan(BigDecimal(239), mc)"), generated)
    }

    @Test
    fun fibonacciExampleValidatesInputAndMemoizesRecursiveResults() {
        val generated = generator.generate("Fibonacci berechnen", Intent.Unknown)

        assertTrue(generated.contains("require(n >= 0)"), generated)
        assertTrue(generated.contains("cache[n]?.let { return it }"), generated)
        assertTrue(generated.contains("cache[n] = value"), generated)
    }
}
