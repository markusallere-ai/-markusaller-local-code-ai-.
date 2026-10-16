package ai

/** Produces concise, fixed Kotlin explanations for common concepts. */
internal class CodeExplanations {
    private val explanationRules: List<Pair<(String) -> Boolean, () -> String>> = listOf(
        ({ prompt: String -> prompt.contains("data class") ||
            TextPatterns.containsWord(prompt, listOf("datenklasse")) }) to
            ::dataClassExplanation,
        {
            prompt: String ->
            TextPatterns.containsWord(prompt, listOf("lambda")) ||
                prompt.contains("funktionale") ||
                prompt.contains("higher order")
        } to ::lambdaExplanation,
        {
            prompt: String ->
            TextPatterns.containsWord(prompt, listOf("nullable")) ||
                TextPatterns.containsWord(prompt, listOf("null"))
        } to ::nullSafetyExplanation,
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("liste", "array", "map", "menge", "set")) }) to
            ::collectionsExplanation,
        {
            prompt: String ->
            TextPatterns.containsWord(prompt, listOf("schleife", "while")) ||
                TextPatterns.containsWord(prompt, listOf("for"))
        } to ::loopExplanation,
        {
            prompt: String ->
            TextPatterns.containsWord(prompt, listOf("pi", "kreiszahl")) ||
                prompt.contains("π")
        } to ::piExplanation,
        ({ prompt: String ->
            TextPatterns.containsWord(prompt, listOf("vererbung", "erben", "ableitung"))
        }) to {
            "Vererbung ermöglicht einem Typ, Eigenschaften und Verhalten eines offenen Basistyps zu übernehmen. " +
                "In Kotlin muss die Basisklasse mit open markiert sein, bevor sie erweitert werden kann.\n\n" +
                "Beispiel:\nopen class Tier\nclass Hund : Tier()\n"
        },
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("variable", "variablen")) }) to {
            "Eine Variable bindet einen Namen an einen Wert. In Kotlin steht val für eine nicht neu zuweisbare " +
                "Referenz; var erlaubt spätere Zuweisungen.\n\nBeispiel:\nval name = \"Ada\"\nvar anzahl = 1\n"
        },
        ({ prompt: String ->
            TextPatterns.containsWord(prompt, listOf("datentyp", "typsystem", "statisch typisiert"))
        }) to {
            "Ein Datentyp legt fest, welche Werte eine Variable aufnehmen kann und welche Operationen zulässig " +
                "sind. Kotlin ist statisch typisiert: Typen werden beim Kompilieren geprüft und können oft aus " +
                "dem Wert abgeleitet werden.\n\nBeispiel:\nval alter: Int = 30\nval name = \"Ada\"\n"
        },
        ({ prompt: String ->
            TextPatterns.containsWord(prompt, listOf("fehlermeldung", "compilerfehler", "exception"))
        }) to {
            "Eine Fehlermeldung beschreibt ein Problem, das etwa beim Kompilieren oder zur Laufzeit erkannt " +
                "wurde. Die genaue Ursache hängt vom Meldungstext und der betroffenen Codezeile ab. " +
                "Füge die vollständige Meldung und den relevanten Code ein, damit ich sie gezielt einordnen kann."
        },
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("kotlin")) }) to ::kotlinExplanation,
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("funktion", "methode")) }) to {
            "Kotlin-Funktionen werden mit fun deklariert. " +
                "Beispiel: fun add(a: Int, b: Int): Int = a + b"
        },
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("klasse", "objekt")) }) to {
            "In Kotlin modellierst du Typen mit class oder data class. " +
                "Beispiel: data class User(val name: String)"
        },
        ({ prompt: String -> TextPatterns.containsWord(prompt, listOf("test", "assert")) }) to {
            "Tests in Kotlin nutzen oft kotlin.test. " +
                "Beispiel: @Test fun example() { assertEquals(2, 1 + 1) }"
        }
    )

    /** Selects an explanation from the Kotlin concepts mentioned in the prompt. */
    fun answerQuestion(prompt: String): String {
        val simplified = normalizePrompt(prompt)
        return explanationRules.firstNotNullOfOrNull { (matches, explanation) ->
            if (matches(simplified)) explanation() else null
        } ?: fallbackExplanation()
    }

    /** Returns a short overview of Kotlin and a minimal function example. */
    private fun kotlinExplanation() =
        "Kotlin ist eine moderne, statisch typisierte Programmiersprache für die JVM, Android " +
            "und native Anwendungen.\n" +
            "Sie unterstützt Funktionen, Klassen, Nullable-Typen, Lambdas und eine sehr gute Java-Interop.\n" +
            "Ein Beispiel für eine einfache Funktion in Kotlin:\n\n" +
            "fun greet(name: String) {\n" +
            "    println(\"Hallo, \$name!\")\n" +
            "}\n"

    /** Explains data classes and names the methods Kotlin generates for them. */
    private fun dataClassExplanation() =
        "In Kotlin erstellt man eine Datenklasse mit dem Schlüsselwort data.\n" +
            "Datenklassen bieten automatisch equals, hashCode und toString.\n\n" +
            "Beispiel:\n" +
            "data class Person(val name: String, val age: Int)\n"

    /** Shows a lambda expression in a small typed example. */
    private fun lambdaExplanation() =
        "Kotlin unterstützt Lambdas und Higher-Order-Funktionen.\n" +
            "Eine Lambda-Funktion sieht so aus:\n\n" +
            "val double: (Int) -> Int = { value -> value * 2 }\n" +
            "println(double(3)) // Ausgabe: 6\n"

    /** Explains nullable and non-nullable types. */
    private fun nullSafetyExplanation() =
        "Kotlin hat ein null-sicheres Typsystem. Ein Typ ohne Fragezeichen kann kein null enthalten.\n" +
            "Beispiel:\n" +
            "val text: String = \"Hallo\"\n" +
            "val maybe: String? = null\n" +
            "if (maybe != null) println(maybe)\n"

    /** Explains list, set, and map collection behavior. */
    private fun collectionsExplanation() =
        "Kotlin-Listen bewahren die Reihenfolge und können Werte mehrfach enthalten; Mengen enthalten " +
            "jedes Element höchstens einmal. Maps ordnen Schlüssel Werten zu.\n" +
            "Beispiel:\n" +
            "val numbers = listOf(1, 2, 2, 3)\n" +
            "val uniqueNumbers = setOf(1, 2, 2, 3) // enthält 1, 2 und 3\n"

    /** Explains common loop forms with a for-loop example. */
    private fun loopExplanation() =
        "Kotlin unterstützt for- und while-Schleifen sowie funktionale Iteration mit forEach.\n" +
            "Beispiel:\n" +
            "for (i in 1..5) {\n" +
            "    println(i)\n" +
            "}\n"

    /** Describes a basic numerical approximation of pi. */
    private fun piExplanation() =
        "Die Kreiszahl Pi kann in Kotlin numerisch berechnet werden.\n" +
            "Eine einfache Methode verwendet die Leibniz-Reihe: Pi = 4 * (1 - 1/3 + 1/5 - 1/7 + ...).\n" +
            "Das ist langsam, aber lokal berechenbar.\n" +
            "Ein Beispielcode kann Pi näherungsweise mit vielen Iterationen berechnen.\n"

    private fun fallbackExplanation() =
        "Ich kann lokale Kotlin-Aufgaben beantworten und Beispielcode erzeugen. " +
            "Formuliere eine konkrete Anfrage wie 'Erstelle eine Datenklasse' oder " +
            "'Erkläre Kotlin Nullable-Typen'."

    /** Normalizes text before keyword checks. */
    private fun normalizePrompt(prompt: String): String = TextFold.fold(prompt)
        .replace(TextPatterns.PUNCTUATION, " ")
        .replace(TextPatterns.WHITESPACE, " ")
        .trim()
}
