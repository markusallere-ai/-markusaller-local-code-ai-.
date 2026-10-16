package ai

/** Generates Kotlin snippets and explanations from classified prompts. */
class CodeGenerator(private val tokenizer: BpeTokenizer) {
    private val explanations = CodeExplanations()
    private val examples = ExampleSnippetGenerator()

    /** Leitet die erkannte Kategorie an den passenden Vorlagen- oder Erklärungszweig weiter. */
    fun generate(prompt: String, intent: Intent): String = generateSnippet(prompt, intent)

    /** Wählt anhand des Intents ein deterministisches Snippet oder eine ehrliche Unknown-Antwort. */
    private fun generateSnippet(prompt: String, intent: Intent): String {
        val normalized = normalizePrompt(prompt)
        if (shouldGenerateSpecializedExample(intent)) {
            generateSpecializedExample(prompt, intent)?.let { return it }
        }
        return when (intent) {
            Intent.Tokenize -> generateTokenization(prompt)
            Intent.Explain -> explanations.answerQuestion(normalized)
            Intent.GenerateTest -> generateTaskWithTest(prompt)
            Intent.GenerateDataClass -> generateDataClassFromPrompt(prompt)
            Intent.GenerateClass -> generateClassFromPrompt(prompt)
            Intent.GenerateFunction -> generateFunctionFromPrompt(prompt)
            Intent.Smalltalk -> "Hallo! Ich bin ein lokaler Kotlin-Code-Assistent. " +
                "Ich kann Kotlin-Klassen, Datenklassen, Funktionen und Tests erstellen oder Code erklaeren."
            Intent.Unknown -> when {
                containsAny(normalized, listOf("summe", "durchschnitt", "sum", "average", "mittelwert")) ->
                    generateFunctionFromPrompt(prompt)
                else -> "Das habe ich nicht verstanden. Ich kann lokal Kotlin-Klassen, Datenklassen, " +
                    "Funktionen und Tests erzeugen oder Kotlin-Code erklaeren. Bitte formuliere eine konkrete Anfrage."
            }
        }
    }

    private fun shouldGenerateSpecializedExample(intent: Intent): Boolean =
        intent == Intent.Unknown || intent == Intent.GenerateFunction

    /** Erzeugt bekannte mathematische Beispiele vor der Intent-Klassifikation, wenn der Prompt eine Aktion verlangt. */
    fun generateSpecializedExample(prompt: String, intent: Intent): String? {
        val normalized = normalizePrompt(prompt)
        if (
            !shouldGenerateSpecializedExample(intent) ||
            TextPatterns.containsWord(normalized, listOf("klasse", "datenklasse", "class", "data class"))
        ) {
            return null
        }
        val requestsCalculation = CALCULATION_ACTION_PATTERN.containsMatchIn(normalized)
        val requestsCircumference = TextPatterns.containsWord(normalized, listOf("umfang", "kreisumfang"))

        return when {
            requestsCalculation && requestsCircumference &&
                containsAny(normalized, listOf("pi", "π")) ->
                examples.generateCircumferenceFunction()
            requestsCalculation &&
                !requestsCircumference &&
                containsAny(normalized, listOf("pi", "π", "kreiszahl")) ->
                examples.generatePiFunction(prompt)
            SORTING_ACTION_PATTERN.containsMatchIn(normalized) ->
                examples.generateSortingFunction(prompt)
            normalized.contains("fibonacci") &&
                (requestsCalculation || TextPatterns.containsWord(normalized, listOf("funktion", "folge"))) ->
                examples.generateFibonacciFunction()
            else -> null
        }
    }

    /** Erzeugt den angefragten Typ/Funktionscode und ergänzt einen einfachen Testentwurf. */
    private fun generateTaskWithTest(p: String): String {
        val normalized = normalizePrompt(p)
        return when {
            TextPatterns.containsWord(normalized, listOf("datenklasse")) || normalized.contains("data class") -> {
                val fields = parseProperties(p).takeIf { it.isNotEmpty() } ?: listOf(Field("name", "String"), Field("age", "Int"))
                val className = extractName(
                    p,
                    listOf("datenklasse", "data class", "klassenmodell", "klasse", "class")
                ) ?: "Person"
                val classCode = generateDataClass(className, fields)
                assembleTestSource(classCode, generateTestForClass(className, fields), "assertEquals")
            }
            TextPatterns.containsWord(normalized, listOf("klasse", "class")) -> {
                val fields = parseProperties(p).takeIf { it.isNotEmpty() } ?: listOf(Field("name", "String"), Field("createdAt", "String"))
                val className = extractName(p, listOf("klasse", "class")) ?: "MyClass"
                val classCode = generateClass(className, fields)
                assembleTestSource(classCode, generateTestForClass(className, fields), "assertEquals")
            }
            TextPatterns.containsWord(normalized, listOf("funktion", "function")) -> {
                val functionName = generatedFunctionName(p, normalized)
                val fields = generatedFunctionParameters(p, normalized)
                val isSum = hasCompatibleOperationParameters(normalized, fields, isSumRequest(normalized))
                val isAverage = hasCompatibleOperationParameters(normalized, fields, isAverageRequest(normalized))
                val functionCode = generateFunctionFromPrompt(p)
                val assertion = if (isSum || isAverage) {
                    "assertEquals"
                } else {
                    "assertTrue"
                }
                assembleTestSource(
                    functionCode,
                    generateSimpleFunctionTest(
                        functionName,
                        fields,
                        isSum,
                        isAverage
                    ),
                    assertion
                )
            }
            else -> generateTestFromPrompt(p)
        }
    }

    /** Leitet Name, Parameter, Rückgabetyp und einfachen Beispielrumpf aus der Anfrage ab. */
    private fun generateFunctionFromPrompt(p: String): String {
        val normalized = normalizePrompt(p)
        val name = generatedFunctionName(p, normalized)
        val fields = generatedFunctionParameters(p, normalized)
        val params = fields.joinToString(", ") { "${it.name}: ${it.type}" }
        val isSum = hasCompatibleOperationParameters(normalized, fields, isSumRequest(normalized))
        val isAverage = hasCompatibleOperationParameters(normalized, fields, isAverageRequest(normalized))
        val isPrintRequest = PRINT_ACTION_PATTERN.containsMatchIn(normalized)

        val requestedReturnType = when {
            TextPatterns.WORD_BOUNDARY_INT.containsMatchIn(normalized) ||
                TextPatterns.WORD_BOUNDARY_ZAHL.containsMatchIn(normalized) -> "Int"
            !isPrintRequest && TextPatterns.WORD_BOUNDARY_STRING.containsMatchIn(normalized) -> "String"
            else -> "Unit"
        }
        val returnType = when {
            isAverage -> "Double"
            isSum -> if (fields.size == 2) fields.first().type else "Int"
            else -> requestedReturnType
        }

        val body = when {
            isSum && fields.size == 2 -> "return ${fields[0].name} + ${fields[1].name}"
            isSum -> "return ${fields.single().name}.sum()"
            isAverage ->
                "require(${fields.single().name}.isNotEmpty()) { \"${fields.single().name} darf nicht leer sein\" }\n" +
                    "    return ${fields.single().name}.average()"
            isPrintRequest -> "println(${fields.firstOrNull()?.name ?: "\"Hallo aus $name\""})"
            returnType == "Int" -> "return 0"
            returnType == "String" -> "return \"\""
            else -> "// TODO: Implementiere deine Logik hier"
        }

        return "fun $name($params)${if (returnType != "Unit") ": $returnType" else ""} {\n    $body\n}\n"
    }

    /** Baut eine Datenklasse aus erkannten Feldern oder nutzt ein minimales Standardbeispiel. */
    private fun generateDataClassFromPrompt(p: String): String {
        val name = extractName(p, listOf("datenklasse", "data class", "klassenmodell", "klasse", "class")) ?: "Person"
        val fields = parseProperties(p).takeIf { it.isNotEmpty() } ?: listOf(Field("name", "String"), Field("age", "Int"))
        return generateDataClass(name, fields)
    }

    /** Gibt Datenklassenfelder als Properties aus, wie es Kotlin für den Primärkonstruktor verlangt. */
    private fun generateDataClass(className: String, fields: List<Field>): String =
        "data class $className(${fields.joinToString(", ") { "val ${it.name}: ${it.type}" }})\n"

    /** Bewahrt einen ausdrücklich genannten Funktionsnamen und leitet sonst einen passenden Namen ab. */
    private fun generatedFunctionName(prompt: String, normalized: String): String {
        val requestedName = extractName(prompt, listOf("funktion", "function"))
            ?.replaceFirstChar { it.lowercase() }
        return when {
            requestedName != null && TextFold.fold(requestedName) !in OPERATION_LABELS -> requestedName
            isSumRequest(normalized) -> "berechneSumme"
            isAverageRequest(normalized) -> "berechneDurchschnitt"
            else -> requestedName ?: "myFunction"
        }
    }

    /** Leitet bekannte Rechenparameter ab und verwendet für andere Aufgaben extrahierte Felder. */
    private fun generatedFunctionParameters(prompt: String, normalized: String): List<Field> {
        val parsed = parseProperties(prompt)
        return when {
            PRINT_ACTION_PATTERN.containsMatchIn(normalized) && parsed.isEmpty() ->
                listOf(Field("text", "String"))
            isSumRequest(normalized) && TWO_SCALAR_SUM_PATTERN.containsMatchIn(normalized) -> {
                val isPrice = PRICE_SUM_TARGET_PATTERN.containsMatchIn(normalized)
                val type = if (isPrice) "Double" else "Int"
                val suffix = if (isPrice) "Price" else "Number"
                listOf(Field("first$suffix", type), Field("second$suffix", type))
            }
            isSumRequest(normalized) && parsed.isEmpty() -> listOf(Field("numbers", "List<Int>"))
            isAverageRequest(normalized) && parsed.isEmpty() -> listOf(Field("values", "List<Double>"))
            else -> parsed
        }
    }

    /** Führt eine Rechenvorlage nur aus, wenn erkannte Parameter zum erwarteten Zahlentyp passen. */
    private fun hasCompatibleOperationParameters(
        normalized: String,
        parameters: List<Field>,
        requested: Boolean
    ): Boolean {
        val hasExpectedParameters = if (isAverageRequest(normalized)) {
            parameters.size == 1 && parameters.single().type == "List<Double>"
        } else {
            parameters.size == 2 && parameters.all { it.type in setOf("Int", "Double") } &&
                parameters.map(Field::type).distinct().size == 1 ||
                parameters.size == 1 && parameters.single().type == "List<Int>"
        }
        return requested && hasExpectedParameters
    }

    /** Erkennt geläufige deutsche und englische Formulierungen für Summen und Mittelwerte. */
    private fun isSumRequest(normalized: String): Boolean =
        TextPatterns.containsWord(normalized, SUM_WORDS)

    private fun isAverageRequest(normalized: String): Boolean =
        TextPatterns.containsWord(normalized, AVERAGE_WORDS)

    /** Baut eine Klasse mit Konstruktorfeldern und einer beschreibenden Methode. */
    private fun generateClassFromPrompt(p: String): String {
        val name = extractName(p, listOf("klassenmodell", "klasse", "class")) ?: "MyClass"
        val fields = parseProperties(p).takeIf { it.isNotEmpty() } ?: listOf(Field("name", "String"), Field("createdAt", "String"))
        return generateClass(name, fields)
    }

    private fun generateClass(name: String, fields: List<Field>): String {
        val constructorParams = fields.joinToString(", ") { "val ${it.name}: ${it.type}" }
        val describeBody = fields.joinToString(" + \", \" + ") { "\"${it.name}=\${${it.name}}\"" }
        return "class $name($constructorParams) {\n" +
            "    fun describe(): String {\n" +
            "        return \"$name(\" + $describeBody + \")\"\n" +
            "    }\n" +
            "}\n"
    }

    /** Erzeugt Konstruktion und Eigenschaftsprüfungen passend zu den bekannten Feldtypen. */
    private fun generateTestForClass(className: String, fields: List<Field>): String {
        val sampleArguments = fields.joinToString(", ") { sampleValueForType(it.type) }
        val propertyChecks = fields.joinToString("\n        ") { field ->
            when (field.type) {
                "String" -> "assertEquals(${sampleValueForType(field.type)}, item.${field.name})"
                "Int" -> "assertEquals(${sampleValueForType(field.type)}, item.${field.name})"
                "Double" -> "assertEquals(${sampleValueForType(field.type)}, item.${field.name}, 0.001)"
                "Boolean" -> "assertEquals(${sampleValueForType(field.type)}, item.${field.name})"
                else -> "assertEquals(${sampleValueForType(field.type)}, item.${field.name})"
            }
        }
        return "// Test für $className\n" +
            "class ${className}Test {\n" +
            "    @Test\n" +
            "    fun test${className}Creation() {\n" +
            "        val item = $className($sampleArguments)\n" +
            "        $propertyChecks\n" +
            "    }\n" +
            "}\n"
    }

    /** Liefert einen für den generierten Test geeigneten Beispielwert je Feldtyp. */
    private fun sampleValueForType(type: String): String = when (type) {
        "String" -> "\"Example\""
        "Int" -> "0"
        "Double" -> "0.0"
        "Boolean" -> "true"
        "List<Int>" -> "listOf(1, 2, 3)"
        "List<Double>" -> "listOf(1.0, 2.0, 3.0)"
        else -> "\"Example\""
    }

    /** Erstellt einen Aufrufbarkeitstest, wenn aus dem Prompt kein genauer Sollwert hervorgeht. */
    private fun generateSimpleFunctionTest(
        functionName: String,
        parameters: List<Field>,
        isSum: Boolean,
        isAverage: Boolean
    ): String {
        val testClass = functionName.replaceFirstChar { it.titlecase() }
        val arguments = if (isSum && parameters.size == 2) {
            "2, 4"
        } else {
            parameters.joinToString(", ") { sampleValueForType(it.type) }
        }
        val (testName, assertion) = when {
            isSum && parameters.size == 2 && parameters.first().type == "Double" ->
                "ReturnsExpectedSum" to "assertEquals(4.0, $functionName(1.5, 2.5), 0.001)"
            isSum -> "ReturnsExpectedSum" to "assertEquals(6, $functionName($arguments))"
            isAverage ->
                "ReturnsExpectedAverage" to "assertEquals(2.0, $functionName($arguments), 0.0001)"
            else -> "IsCallable" to
                "assertTrue(runCatching { $functionName($arguments) }.isSuccess)"
        }
        return "// Test für Funktion $functionName\n" +
            "class ${testClass}Test {\n" +
            "    @Test\n" +
            "    fun test${testClass}$testName() {\n" +
            "        $assertion\n" +
            "    }\n" +
            "}\n"
    }

    /** Setzt benötigte Imports vor die Implementierung, an die Kotlin-Dateisyntax es bindet. */
    private fun assembleTestSource(implementation: String, test: String, assertion: String): String =
        "import kotlin.test.Test\nimport kotlin.test.$assertion\n\n$implementation\n$test"

    /** Liefert ein minimales Testgerüst, falls die Anfrage keine Klasse oder Funktion spezifiziert. */
    private fun generateTestFromPrompt(p: String): String {
        val className = extractName(p, listOf("test für", "test fuer", "test for")) ?: "ExampleTest"
        return "// TODO: Ersetze diesen absichtlich fehlschlagenden Platzhalter durch eine fachliche Assertion.\n" +
            "import kotlin.test.Test\n\nclass $className {\n" +
            "    @Test\n" +
            "    fun testExample() {\n" +
            "        TODO(\"Zu testendes Verhalten und erwartetes Ergebnis ergänzen\")\n" +
            "    }\n" +
            "}\n"
    }

    /** Extrahiert die nach „mit“ genannten Attribute und verwirft nicht erkennbare Namen. */
    private fun parseProperties(p: String): List<Field> {
        val mitMatch = TextPatterns.WORD_BOUNDARY_MIT.find(p) ?: return emptyList()
        val afterMit = p.substring(mitMatch.range.last + 1).trim()
        if (afterMit.isBlank()) return emptyList()

        return afterMit.split(TextPatterns.PROPERTY_SPLIT)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val cleaned = raw.replace(TextPatterns.ARTICLE_WORDS, "").trim()
                val parsedName = if (TextPatterns.EMAIL_FIELD.containsMatchIn(cleaned)) {
                    "email"
                } else {
                    TextPatterns.FIELD_NAME.find(cleaned)?.groupValues?.get(1)?.lowercase()
                }
                val fieldName = parsedName
                    ?.replace(TextPatterns.NON_ALNUM, "")
                    ?.let { if (it == "alter") "age" else it }
                if (fieldName.isNullOrBlank()) return@mapNotNull null
                Field(TextPatterns.sanitizeKotlinIdentifier(fieldName), guessFieldType(cleaned))
            }
            .distinctBy(Field::name)
    }

    /** Schätzt einen primitiven Kotlin-Typ anhand einfacher Fachwörter; unbekannte Felder werden String. */
    private fun guessFieldType(raw: String): String {
        val lower = TextFold.fold(raw)
        return when {
            TextPatterns.EMAIL_FIELD.containsMatchIn(lower) ||
                TextPatterns.containsWord(lower, listOf("email", "name", "text", "string")) -> "String"
            TextPatterns.containsWord(lower, listOf("alter", "age", "id", "nummer", "count", "zahl", "anzahl")) -> "Int"
            TextPatterns.containsWord(lower, listOf("preis", "betrag", "wert", "temperatur")) -> "Double"
            TextPatterns.containsWord(lower, listOf("portion", "portionen")) -> "Int"
            TextPatterns.containsWord(lower, listOf("ist", "aktiv", "valid", "bool")) -> "Boolean"
            else -> "String"
        }
    }

    /** Liest einen Namen hinter einem möglichen Schlüsselwort und bereinigt ihn als Kotlin-Bezeichner. */
    private fun extractName(p: String, keys: List<String>): String? {
        val match = keys.mapNotNull { key ->
            Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE)
                .find(p)
                ?.let { it.range.first to it.range.last + 1 }
        }.minByOrNull { it.first } ?: return null
        return nameAfter(p.substring(match.second))
    }

    /** Überspringt Füllwörter und gibt das erste passende Kotlin-Bezeichnerwort zurück. */
    private fun nameAfter(text: String): String? {
        var rest = text.trimStart()
        var result: String? = null
        while (rest.isNotEmpty() && result == null) {
            val match = TextPatterns.FIELD_NAME.find(rest)
            if (match == null) {
                rest = ""
            } else {
                val candidate = match.groupValues[1]
                if (TextFold.fold(candidate) !in NAME_FILLER_WORDS) {
                    result = TextPatterns.sanitizeKotlinIdentifier(capitalize(candidate))
                } else {
                    rest = rest.substring(match.range.last + 1).trimStart()
                }
            }
        }
        return result
    }


    /** Entfernt Satzzeichen und vereinheitlicht die Anfrage für Schlüsselworttests. */
    private fun normalizePrompt(p: String): String = TextFold.fold(p)
        .replace(TextPatterns.PUNCTUATION, " ")
        .replace(TextPatterns.WHITESPACE, " ")
        .trim()

    /** Prüft, ob mindestens eines der Schlüsselwörter als vollständiges Wort vorkommt. */
    private fun containsAny(p: String, keywords: List<String>): Boolean = TextPatterns.containsWord(p, keywords)

    /** Wandelt den ersten Buchstaben in Großschreibung um, ohne den restlichen Namen zu verändern. */
    private fun capitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** Zeigt Token, ID und einen kurzen Embedding-Ausschnitt für den Eingabetext. */
    private fun generateTokenization(prompt: String): String {
        val withoutAction = TextPatterns.TOKENIZE_PREFIX.replace(prompt, "").trim()
        val cleanedText = TextPatterns.TOKENIZE_TEXT_PREFIX.replace(withoutAction, "").trim()
        val text = if (cleanedText.isBlank()) prompt else cleanedText
        val tokens = tokenizer.tokenize(text)
        val ids = tokenizer.encode(text)
        return buildString {
            append("BPE-Tokenisierung mit festem Vokabular (${tokenizer.vocabSize()} Einträge)\n")
            append("Text: $text\n\n")
            append("Tokenliste mit ID und Embedding (${tokenizer.embeddingDimension()}D):\n")
            tokens.zip(ids).forEachIndexed { index, (token, id) ->
                val preview = tokenizer.embeddingForId(id)
                    .take(6)
                    .joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
                append("${index + 1}. $id: '$token' -> $preview\n")
            }
        }
    }

    /** Ein aus dem Prompt erkanntes Konstruktor- oder Datenklassenfeld. */
    data class Field(val name: String, val type: String)

    private companion object {
        val NAME_FILLER_WORDS = setOf(
            "ein", "eine", "einen", "einem", "einer", "eines", "namens", "name",
            "fuer", "für", "die", "der", "den", "dem", "das", "des", "zum", "zur", "for"
        )
        val SUM_WORDS = listOf("summe", "sum", "addiere", "addieren", "summiere", "summieren", "aufsummieren")
        val AVERAGE_WORDS = listOf("durchschnitt", "mittelwert", "average", "mean")
        val TWO_SCALAR_SUM_PATTERN = Regex("\\b(zwei|2|two)\\s+(zahlen?|werte?|preise?n?|betraege?n?)\\b")
        val PRICE_SUM_TARGET_PATTERN = Regex("\\b(preise?n?|betraege?n?)\\b")
        val PRINT_ACTION_PATTERN = Regex("\\b(print|println)\\w*\\b")
        val CALCULATION_ACTION_PATTERN = Regex("\\b(berechn\\w*|calculate\\w*|ermittel\\w*)\\b")
        val SORTING_ACTION_PATTERN = Regex("\\b(sortier\\w*|ordne\\w*)\\b")
        val OPERATION_LABELS = setOf(
            "summe", "summieren", "summiere", "durchschnitt", "mittelwert", "average", "mean"
        )
    }
}
