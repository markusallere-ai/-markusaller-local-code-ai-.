package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests for Kotlin snippets and tests produced from prompts. */
class CodeGeneratorTest {
    private val generator = CodeGenerator(BpeTokenizer())

    @Test
    fun generatedFunctionPrintsItsTextParameter() {
        val generated = generator.generate(
            "Erstelle eine Funktion, die einen Text printet",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun myFunction(text: String)"), generated)
        assertTrue(generated.contains("println(text)"), generated)
        assertFalse(generated.contains("return \"\""), generated)
    }

    @Test
    fun classModelNameExtractionUsesWholeWordsAndSkipsArticles() {
        val generated = generator.generate(
            "Erzeuge ein Klassenmodell fuer einen Auftrag mit Eigenschaften",
            Intent.GenerateDataClass
        )
        val classOutput = generator.generate(
            "Erzeuge ein Klassenmodell fuer einen Auftrag mit Eigenschaften",
            Intent.GenerateClass
        )

        assertTrue(generated.startsWith("data class Auftrag("), generated)
        assertFalse(generated.contains("Nmodell"), generated)
        assertTrue(classOutput.startsWith("class Auftrag("), classOutput)
        assertFalse(classOutput.contains("Nmodell"), classOutput)
    }

    @Test
    fun generatedClassAndItsTestUseTheSameFallbackName() {
        val generated = generator.generate("Erstelle einen Test für die Klasse", Intent.GenerateTest)

        assertTrue(generated.contains("class MyClass("), generated)
        assertTrue(generated.contains("class MyClassTest"), generated)
        assertTrue(generated.contains("val item = MyClass("), generated)
        assertFalse(generated.contains("class Test("), generated)
    }

    @Test
    fun generatedSumFunctionUsesAParameterizedIntegerList() {
        val generated = generator.generate(
            "Erstelle eine Funktion zur summe als int zahl",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun berechneSumme(numbers: List<Int>): Int"), generated)
        assertTrue(generated.contains("return numbers.sum()"), generated)
    }

    @Test
    fun generatedTwoPriceSumUsesDoubleParametersAndExpectedResult() {
        val generated = generator.generate(
            "Berechne die Summe von zwei Preisen",
            Intent.GenerateFunction
        )
        val generatedTest = generator.generate(
            "Erstelle einen Test für eine Funktion zur Summe von zwei Preisen",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("fun berechneSumme(firstPrice: Double, secondPrice: Double): Double"), generated)
        assertTrue(generated.contains("return firstPrice + secondPrice"), generated)
        assertTrue(generatedTest.contains("assertEquals(4.0, berechneSumme(1.5, 2.5), 0.001)"), generatedTest)
    }

    @Test
    fun generatedKnownPropertyNamesUseNumericTypes() {
        val weather = generator.generate(
            "Erzeuge eine Datenklasse Wetterbericht mit Ort und Temperatur",
            Intent.GenerateDataClass
        )
        val recipe = generator.generate(
            "Erstelle eine Datenklasse Rezept mit Name und Portionen",
            Intent.GenerateDataClass
        )

        assertTrue(weather.contains("val temperatur: Double"), weather)
        assertTrue(recipe.contains("val portionen: Int"), recipe)
    }

    @Test
    fun generatedDataClassFieldsAreKotlinProperties() {
        val generated = generator.generate(
            "Erstelle eine Datenklasse Benutzer mit Name und Alter",
            Intent.GenerateDataClass
        )

        assertTrue(generated.contains("data class Benutzer(val name: String, val age: Int)"), generated)
    }

    @Test
    fun generatedDataClassTestUsesCompilableProperties() {
        val generated = generator.generate(
            "Erstelle einen Test für eine Datenklasse Benutzer mit Name und Alter",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("data class Benutzer(val name: String, val age: Int)"), generated)
        assertTrue(generated.indexOf("import kotlin.test.Test") < generated.indexOf("data class"), generated)
        assertFalse(generated.substringAfter("data class").contains("import kotlin.test"), generated)
        assertTrue(generated.contains("item.name"), generated)
        assertTrue(generated.contains("item.age"), generated)
    }

    @Test
    fun generatedClassTestUsesTypedDoubleAndBooleanAssertions() {
        val generated = generator.generate(
            "Erstelle einen Test für eine Klasse Tarif mit Preis und Aktiv",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("val preis: Double"), generated)
        assertTrue(generated.contains("val aktiv: Boolean"), generated)
        assertTrue(generated.contains("assertEquals(0.0, item.preis, 0.001)"), generated)
        assertTrue(generated.contains("assertEquals(true, item.aktiv)"), generated)
    }

    @Test
    fun generatedAverageFunctionRejectsEmptyInput() {
        val generated = generator.generate(
            "Erstelle eine Funktion für den Durchschnitt",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun berechneDurchschnitt(values: List<Double>): Double"), generated)
        assertTrue(generated.contains("return values.average()"), generated)
        assertTrue(generated.contains("require(values.isNotEmpty())"), generated)
    }

    @Test
    fun unknownSumFallbackUsesParameterizedFunctionInsteadOfFixedDemoData() {
        val generated = generator.generate("summe", Intent.Unknown)

        assertTrue(generated.contains("fun berechneSumme(numbers: List<Int>): Int"), generated)
        assertTrue(generated.contains("return numbers.sum()"), generated)
        assertFalse(generated.contains("fun main()"), generated)
    }

    @Test
    fun generatedSumFunctionTestCallsFunctionWithItsRequiredListArgument() {
        val generated = generator.generate(
            "Erstelle einen Test für eine Funktion zur Summe",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("assertEquals(6, berechneSumme(listOf(1, 2, 3)))"), generated)
    }

    @Test
    fun generatedAverageTestChecksTheNumericalResult() {
        val generated = generator.generate(
            "Erstelle einen Test für eine Funktion für den Durchschnitt",
            Intent.GenerateTest
        )

        assertTrue(
            generated.contains(
                "assertEquals(2.0, berechneDurchschnitt(listOf(1.0, 2.0, 3.0)), 0.0001)"
            ),
            generated
        )
    }

    @Test
    fun explicitTwoNumberAdditionGetsScalarParametersAndCorrectBody() {
        val generated = generator.generate(
            "Erstelle eine Funktion addiere mit zwei Zahlen",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun addiere(firstNumber: Int, secondNumber: Int): Int"), generated)
        assertTrue(generated.contains("return firstNumber + secondNumber"), generated)
    }

    @Test
    fun explicitIntegerFieldsAreUsedByTheSumBody() {
        val generated = generator.generate(
            "Erstelle eine Funktion addiere mit Zahl und Alter",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun addiere(zahl: Int, age: Int): Int"), generated)
        assertTrue(generated.contains("return zahl + age"), generated)
    }

    @Test
    fun unsupportedSingleScalarSumDoesNotReferenceMissingListParameter() {
        val generated = generator.generate(
            "Erstelle eine Funktion zur Summe mit Alter",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("fun berechneSumme(age: Int)"), generated)
        assertFalse(generated.contains("numbers.sum()"), generated)
    }

    @Test
    fun unsupportedThreeScalarSumDoesNotSilentlyAddOnlyTwoFields() {
        val generated = generator.generate(
            "Erstelle eine Funktion addiere mit Zahl, Alter und ID",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("zahl: Int"), generated)
        assertTrue(generated.contains("age: Int"), generated)
        assertTrue(generated.contains("id: Int"), generated)
        assertFalse(generated.contains("return zahl + age"), generated)
    }

    @Test
    fun averageWithAnExplicitScalarFieldDoesNotReferenceMissingValuesList() {
        val generated = generator.generate(
            "Erstelle eine Funktion für den Durchschnitt mit Preis",
            Intent.GenerateFunction
        )

        assertTrue(generated.contains("preis: Double"), generated)
        assertFalse(generated.contains("values.average()"), generated)
    }

    @Test
    fun testForTwoNumberAdditionUsesNonzeroInputsAndChecksTheSum() {
        val generated = generator.generate(
            "Erstelle einen Test für die Funktion addiere mit zwei Zahlen",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("assertEquals(6, addiere(2, 4))"), generated)
    }

    @Test
    fun extractNameSupportsUmlauts() {
        val generated = generator.generate(
            "Erstelle eine datenklasse Größe mit breite und hoehe",
            Intent.GenerateDataClass
        )

        assertTrue(generated.startsWith("data class Größe("), generated)
    }

    @Test
    fun propertySeparatorOnlySplitsTheWordUndNotTheMiddleOfKunde() {
        val generated = generator.generate(
            "Erstelle eine Datenklasse Bestellung mit Kunde UND E-Mail",
            Intent.GenerateDataClass
        )

        assertTrue(generated.contains("val kunde: String"), generated)
        assertTrue(generated.contains("val email: String"), generated)
        assertFalse(generated.contains("val k:"), generated)
        assertFalse(generated.contains("val e:"), generated)
    }

    @Test
    fun repeatedPropertyNamesProduceOnlyOneConstructorField() {
        val generated = generator.generate(
            "Erstelle eine Datenklasse Person mit Name und name",
            Intent.GenerateDataClass
        )

        assertEquals(1, Regex("val name: String").findAll(generated).count())
    }

    @Test
    fun kotlinKeywordPropertyNamesAreSanitized() {
        val generated = generator.generate(
            "Erstelle eine Datenklasse Person mit class und Name",
            Intent.GenerateDataClass
        )

        assertTrue(generated.contains("val classItem: String"), generated)
    }

    @Test
    fun generatedTestUsesSafeDefaultNameWhenNoSubjectIsSpecified() {
        val generated = generator.generate("Erstelle einen Test", Intent.GenerateTest)

        assertTrue(generated.contains("class ExampleTest"), generated)
        assertFalse(generated.contains("class Fuer"), generated)
    }

    @Test
    fun generatedTestExtractsSubjectAfterTestFor() {
        val generated = generator.generate(
            "Erstelle einen Test für einen Parser",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("class Parser"), generated)
    }

    @Test
    fun generatedFunctionTestPassesPlaceholderArgumentsForParsedParameters() {
        val generated = generator.generate(
            "Erstelle einen Test für die Funktion addiere mit Zahl und Name",
            Intent.GenerateTest
        )

        assertTrue(generated.contains("fun addiere(zahl: Int, name: String)"), generated)
        assertTrue(generated.contains("runCatching { addiere(0, \"Example\") }"), generated)
        assertFalse(generated.contains("return zahl + name"))
    }

    @Test
    fun generatedNamesSkipArticlesAndExplicitNameMarkers() {
        val generated = generator.generate(
            "Erstelle eine Klasse namens Benutzer",
            Intent.GenerateClass
        )

        assertTrue(generated.contains("class Benutzer("), generated)
        assertFalse(generated.contains("class Eine("), generated)
    }

    @Test
    fun generatedTestWithoutSubjectIsExplicitlyIncomplete() {
        val generated = generator.generate("Erstelle einen Test", Intent.GenerateTest)

        assertTrue(generated.contains("TODO(\"Zu testendes Verhalten"))
        assertFalse(generated.contains("assertNotNull(\"kotlin\")"))
    }
}
