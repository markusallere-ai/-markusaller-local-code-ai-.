package ai

import java.math.BigDecimal
import java.math.RoundingMode

/** Generates Kotlin snippets and explanations from classified prompts. */
@Suppress("LargeClass")
class CodeGenerator(private val tokenizer: BpeTokenizer) {
    private val explanations = CodeExplanations()
    private val examples = ExampleSnippetGenerator()

    /** Leitet die erkannte Kategorie an den passenden Vorlagen- oder Erklärungszweig weiter. */
    fun generate(prompt: String, intent: Intent): String = generateSnippet(prompt, intent)

    private enum class GeneratedFunctionOperation {
        MAX,
        MIN,
        DIFFERENCE,
        TEMPERATURE
    }

    private data class GeneratedFunctionTestOperation(
        val operation: GeneratedFunctionOperation,
        val fahrenheitToCelsius: Boolean
    )

    private data class TemperatureQuestion(
        val input: BigDecimal,
        val sourceIsCelsius: Boolean,
        val targetFahrenheit: Boolean
    )

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
            Intent.Smalltalk -> ConversationResponder.reply(prompt)
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
        return generateTemperatureAnswer(prompt) ?: generateNonTemperatureSpecializedExample(prompt, intent)
    }

    private fun generateNonTemperatureSpecializedExample(prompt: String, intent: Intent): String? {
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

    /** Answers explicit numeric temperature questions instead of generating a reusable function. */
    private fun generateTemperatureAnswer(prompt: String): String? {
        val question = parseTemperatureQuestion(prompt) ?: return null
        return formatTemperatureAnswer(question)
    }

    private fun parseTemperatureQuestion(prompt: String): TemperatureQuestion? {
        val targetFahrenheit = Regex("\\b(in|zu|nach|to)\\s+(?:grad\\s+)?fahrenheit\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(prompt) ||
            Regex("°\\s*f\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val targetCelsius = Regex("\\b(in|zu|nach|to)\\s+(?:grad\\s+)?celsius\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(prompt) ||
            Regex("°\\s*c\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val input = Regex("(?<![\\p{L}\\p{N}_])[+-]?\\d+(?:[.,]\\d+)?(?![\\p{L}\\p{N}_])")
            .find(prompt)?.value?.replace(',', '.')?.toBigDecimalOrNull()
        val hasTemperatureUnit = TextPatterns.containsTemperatureTerm(normalizePrompt(prompt)) ||
            Regex("°|\\bgrad\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val isCodeRequest = TextPatterns.containsWord(
            normalizePrompt(prompt),
            listOf(
                "funktion", "function", "code", "programm", "erstelle", "erzeuge",
                "schreibe", "schreib", "implementiere", "implementier", "generiere",
                "generier", "klasse", "class"
            )
        )
        val isConversionCommand = TextPatterns.isTemperatureConversionRequest(normalizePrompt(prompt))
        val targetStart = listOf(
            Regex("\\b(in|zu|nach|to)\\s+(?:grad\\s+)?(?:fahrenheit|celsius)\\b", RegexOption.IGNORE_CASE),
            Regex("°\\s*[fc]\\b", RegexOption.IGNORE_CASE)
        ).mapNotNull { it.find(prompt)?.range?.first }.minOrNull() ?: prompt.length
        val explicitCelsiusSource =
            Regex("°\\s*c\\b|\\bcelsius\\b", RegexOption.IGNORE_CASE)
                .findAll(prompt).any { it.range.first < targetStart }
        val explicitFahrenheitSource =
            Regex("°\\s*f\\b|\\bfahrenheit\\b", RegexOption.IGNORE_CASE)
                .findAll(prompt).any { it.range.first < targetStart }
        val sourceIsCelsius = when {
            explicitCelsiusSource -> true
            explicitFahrenheitSource -> false
            else -> targetFahrenheit
        }
        if (targetFahrenheit == targetCelsius || input == null) return null
        val isSupportedQuestion =
            !isCodeRequest && !isConversionCommand && hasTemperatureUnit &&
                sourceIsCelsius == targetFahrenheit
        return if (isSupportedQuestion) {
            TemperatureQuestion(input, sourceIsCelsius, targetFahrenheit)
        } else {
            null
        }
    }

    private fun formatTemperatureAnswer(question: TemperatureQuestion): String {
        val result = if (question.sourceIsCelsius) {
            question.input.multiply(BigDecimal("1.8")).add(BigDecimal("32"))
        } else {
            question.input.subtract(BigDecimal("32"))
                .divide(BigDecimal("1.8"), 2, RoundingMode.HALF_UP)
        }.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val sourceUnit = if (question.sourceIsCelsius) "°C" else "°F"
        val targetUnit = if (question.targetFahrenheit) "°F" else "°C"
        val inputText = question.input.stripTrailingZeros().toPlainString()
        return "$inputText $sourceUnit entsprechen $result $targetUnit."
    }

    /** Erzeugt den angefragten Typ/Funktionscode und ergänzt einen einfachen Testentwurf. */
    @Suppress("ComplexMethod", "LongMethod")
    private fun generateTaskWithTest(p: String): String {
        val normalized = normalizePrompt(p)
        return when {
            TextPatterns.containsWord(normalized, listOf("datenklasse")) || normalized.contains("data class") -> {
                val fields = parseProperties(p).takeIf { it.isNotEmpty() }
                    ?: listOf(Field("name", "String"), Field("age", "Int"))
                val className = extractName(
                    p,
                    listOf("datenklasse", "data class", "klassenmodell", "klasse", "class")
                ) ?: "Person"
                val classCode = generateDataClass(className, fields)
                assembleTestSource(classCode, generateTestForClass(className, fields), "assertEquals")
            }
            TextPatterns.containsWord(normalized, listOf("klasse", "class")) -> {
                val fields = parseProperties(p).takeIf { it.isNotEmpty() }
                    ?: listOf(Field("name", "String"), Field("createdAt", "String"))
                val className = extractName(p, listOf("klasse", "class")) ?: "MyClass"
                val classCode = generateClass(className, fields)
                assembleTestSource(classCode, generateTestForClass(className, fields), "assertEquals")
            }
            TextPatterns.containsWord(normalized, listOf("funktion", "function")) -> {
                val fallbackFunctionName = generatedFunctionName(p, normalized)
                val fields = generatedFunctionParameters(p, normalized)
                val isSum = hasCompatibleOperationParameters(normalized, fields, isSumRequest(normalized))
                val isAverage = hasCompatibleOperationParameters(normalized, fields, isAverageRequest(normalized))
                val operation = resolveGeneratedFunctionOperation(normalized)
                val functionName = operation?.let { generatedOperationName(p, normalized, it) }
                    ?: fallbackFunctionName
                val functionCode = generateFunctionFromPrompt(p)
                val assertion = if (isSum || isAverage || operation != null) {
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
                        isAverage,
                        operation?.let {
                            GeneratedFunctionTestOperation(
                                it,
                                it == GeneratedFunctionOperation.TEMPERATURE &&
                                    isFahrenheitToCelsius(normalized)
                            )
                        }
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
        val operation = resolveGeneratedFunctionOperation(normalized)
        if (operation != null) {
            return generateFunctionOperation(p, normalized, operation)
        }

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

    @Suppress("ComplexMethod", "LongMethod")
    private fun generateFunctionOperation(
        prompt: String,
        normalized: String,
        operation: GeneratedFunctionOperation
    ): String {
        val name = generatedOperationName(prompt, normalized, operation)

        return when (operation) {
            GeneratedFunctionOperation.MAX -> {
                val valuesName = "numbers"
                val usesDecimal = usesDecimalOperationValues(normalized)
                val elementType = if (usesDecimal) "List<Double>" else "List<Int>"
                val returnType = if (usesDecimal) "Double" else "Int"
                val fallback = if (usesDecimal) "0.0" else "0"
                "fun $name($valuesName: $elementType): $returnType {\n" +
                    "    require($valuesName.isNotEmpty()) { \"$valuesName darf nicht leer sein\" }\n" +
                    "    return $valuesName.maxOrNull() ?: $fallback\n" +
                    "}\n"
            }
            GeneratedFunctionOperation.MIN -> {
                val valuesName = "numbers"
                val usesDecimal = usesDecimalOperationValues(normalized)
                val elementType = if (usesDecimal) "List<Double>" else "List<Int>"
                val returnType = if (usesDecimal) "Double" else "Int"
                val fallback = if (usesDecimal) "0.0" else "0"
                "fun $name($valuesName: $elementType): $returnType {\n" +
                    "    require($valuesName.isNotEmpty()) { \"$valuesName darf nicht leer sein\" }\n" +
                    "    return $valuesName.minOrNull() ?: $fallback\n" +
                    "}\n"
            }
            GeneratedFunctionOperation.DIFFERENCE -> {
                val usesDecimal = usesDecimalOperationValues(normalized)
                val firstName = if (usesDecimal) "firstPrice" else "firstNumber"
                val secondName = if (usesDecimal) "secondPrice" else "secondNumber"
                val type = if (usesDecimal) "Double" else "Int"
                "fun $name($firstName: $type, $secondName: $type): $type {\n" +
                    "    return $firstName - $secondName\n" +
                    "}\n"
            }
            GeneratedFunctionOperation.TEMPERATURE -> {
                val isFahrenheitToCelsius = isFahrenheitToCelsius(normalized)
                val inputName = if (isFahrenheitToCelsius) "fahrenheit" else "celsius"
                val expression = if (isFahrenheitToCelsius) {
                    "(fahrenheit - 32.0) * 5.0 / 9.0"
                } else {
                    "(celsius * 9.0 / 5.0) + 32.0"
                }
                "fun $name($inputName: Double): Double {\n" +
                    "    return $expression\n" +
                    "}\n"
            }
        }
    }

    private fun generatedOperationName(
        prompt: String,
        normalized: String,
        operation: GeneratedFunctionOperation
    ): String {
        val explicitName = extractName(prompt, listOf("funktion", "function"))
            ?.replaceFirstChar { it.lowercase() }
        return explicitName ?: when (operation) {
            GeneratedFunctionOperation.MAX -> "berechneMaximum"
            GeneratedFunctionOperation.MIN -> "berechneMinimum"
            GeneratedFunctionOperation.DIFFERENCE -> "berechneDifferenz"
            GeneratedFunctionOperation.TEMPERATURE ->
                if (isFahrenheitToCelsius(normalized)) "fahrenheitToCelsius" else "celsiusToFahrenheit"
        }
    }

    private fun isFahrenheitToCelsius(normalized: String): Boolean =
        containsAny(normalized, listOf("fahrenheit")) &&
            (
                Regex("\\bfahrenheit\\s+(in|zu|nach|to)\\s+celsius\\b").containsMatchIn(normalized) ||
                    !containsAny(normalized, listOf("celsius"))
                )

    private fun usesDecimalOperationValues(normalized: String): Boolean =
        containsAny(
            normalized,
            listOf(
                "preis", "preise", "preisen", "betrag", "betraege", "betraegen",
                "double", "komma", "dezimal", "decimal"
            )
        )

    private fun resolveGeneratedFunctionOperation(normalized: String): GeneratedFunctionOperation? = when {
        TextPatterns.containsWord(normalized, MAX_WORDS) -> GeneratedFunctionOperation.MAX
        TextPatterns.containsWord(normalized, MIN_WORDS) -> GeneratedFunctionOperation.MIN
        TextPatterns.containsWord(normalized, DIFFERENCE_WORDS) -> GeneratedFunctionOperation.DIFFERENCE
        isTemperatureConversionRequest(normalized) -> GeneratedFunctionOperation.TEMPERATURE
        else -> null
    }

    private fun isTemperatureConversionRequest(normalized: String): Boolean =
        TextPatterns.isTemperatureConversionRequest(normalized)

    /** Baut eine Datenklasse aus erkannten Feldern oder nutzt ein minimales Standardbeispiel. */
    private fun generateDataClassFromPrompt(p: String): String {
        val name = extractName(p, listOf("datenklasse", "data class", "klassenmodell", "klasse", "class")) ?: "Person"
        val fields = parseProperties(p).takeIf { it.isNotEmpty() }
            ?: listOf(Field("name", "String"), Field("age", "Int"))
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
        val operation = resolveGeneratedFunctionOperation(normalized)
        if (operation != null) return operationParameters(operation, normalized)

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

    private fun operationParameters(
        operation: GeneratedFunctionOperation,
        normalized: String
    ): List<Field> {
        val decimal = usesDecimalOperationValues(normalized)
        return when (operation) {
            GeneratedFunctionOperation.MAX, GeneratedFunctionOperation.MIN ->
                listOf(Field("numbers", if (decimal) "List<Double>" else "List<Int>"))
            GeneratedFunctionOperation.DIFFERENCE -> {
                val type = if (decimal) "Double" else "Int"
                val suffix = if (decimal) "Price" else "Number"
                listOf(Field("first$suffix", type), Field("second$suffix", type))
            }
            GeneratedFunctionOperation.TEMPERATURE ->
                listOf(Field(if (isFahrenheitToCelsius(normalized)) "fahrenheit" else "celsius", "Double"))
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
        val fields = parseProperties(p).takeIf { it.isNotEmpty() }
            ?: listOf(Field("name", "String"), Field("createdAt", "String"))
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
        isAverage: Boolean,
        operation: GeneratedFunctionTestOperation? = null
    ): String {
        val testClass = functionName.replaceFirstChar { it.titlecase() }
        val arguments = if (isSum && parameters.size == 2) {
            "2, 4"
        } else {
            parameters.joinToString(", ") { sampleValueForType(it.type) }
        }
        val (testName, assertion) = when (operation?.operation) {
            GeneratedFunctionOperation.MAX -> extremumTest(functionName, parameters, largest = true)
            GeneratedFunctionOperation.MIN -> extremumTest(functionName, parameters, largest = false)
            GeneratedFunctionOperation.DIFFERENCE -> differenceTest(functionName, parameters)
            GeneratedFunctionOperation.TEMPERATURE ->
                temperatureTest(functionName, operation.fahrenheitToCelsius)
            null -> when {
                isSum && parameters.size == 2 && parameters.first().type == "Double" ->
                    "ReturnsExpectedSum" to "assertEquals(4.0, $functionName(1.5, 2.5), 0.001)"
                isSum -> "ReturnsExpectedSum" to "assertEquals(6, $functionName($arguments))"
                isAverage ->
                    "ReturnsExpectedAverage" to "assertEquals(2.0, $functionName($arguments), 0.0001)"
                else -> "IsCallable" to
                    "assertTrue(runCatching { $functionName($arguments) }.isSuccess)"
            }
        }
        return "// Test für Funktion $functionName\n" +
            "class ${testClass}Test {\n" +
            "    @Test\n" +
            "    fun test${testClass}$testName() {\n" +
            "        $assertion\n" +
            "    }\n" +
            "}\n"
    }

    private fun temperatureTest(
        functionName: String,
        fahrenheitToCelsius: Boolean
    ): Pair<String, String> {
        val (input, expected) = if (fahrenheitToCelsius) {
            "77.0" to "25.0"
        } else {
            "25.0" to "77.0"
        }
        return "ReturnsConvertedTemperature" to
            "assertEquals($expected, $functionName($input), 0.0001)"
    }

    private fun extremumTest(
        functionName: String,
        parameters: List<Field>,
        largest: Boolean
    ): Pair<String, String> {
        val decimal = parameters.singleOrNull()?.type == "List<Double>"
        val values = if (decimal) "listOf(3.0, 8.0, 1.0)" else "listOf(3, 8, 1)"
        val expected = if (largest) {
            if (decimal) "8.0" else "8"
        } else {
            if (decimal) "1.0" else "1"
        }
        val name = if (largest) "ReturnsLargestValue" else "ReturnsSmallestValue"
        return name to "assertEquals($expected, $functionName($values))"
    }

    private fun differenceTest(functionName: String, parameters: List<Field>): Pair<String, String> {
        val decimal = parameters.firstOrNull()?.type == "Double"
        val (first, second, expected) = if (decimal) {
            Triple("5.0", "3.0", "2.0")
        } else {
            Triple("5", "3", "2")
        }
        return "ReturnsExpectedDifference" to
            "assertEquals($expected, $functionName($first, $second))"
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
    private fun capitalize(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
        val MAX_WORDS = listOf(
            "maximum", "maximal", "groesste", "groessten", "groesster", "groesstes", "groest", "largest", "max"
        )
        val MIN_WORDS = listOf(
            "minimum", "minimal", "kleinste", "kleinsten", "kleinster", "kleinstes", "smallest", "min"
        )
        val DIFFERENCE_WORDS = listOf("differenz", "difference", "delta", "subtraktion", "subtrahiere", "subtract")
        val SUM_WORDS = listOf("summe", "sum", "addiere", "addieren", "summiere", "summieren", "aufsummieren")
        val AVERAGE_WORDS = listOf("durchschnitt", "mittelwert", "average", "mean")
        val TWO_SCALAR_SUM_PATTERN = Regex("\\b(zwei|2|two)\\s+(zahlen?|werte?|preise?n?|betraege?n?)\\b")
        val PRICE_SUM_TARGET_PATTERN = Regex("\\b(preise?n?|betraege?n?)\\b")
        val PRINT_ACTION_PATTERN = Regex("\\b(print|println)\\w*\\b")
        val CALCULATION_ACTION_PATTERN = Regex("\\b(berechn\\w*|calculate\\w*|ermittel\\w*)\\b")
        val SORTING_ACTION_PATTERN = Regex("\\b(sortier\\w*|ordne\\w*)\\b")
        val OPERATION_LABELS = setOf(
            "summe", "summieren", "summiere", "durchschnitt", "mittelwert", "average", "mean",
            "maximum", "minimum", "differenz"
        )
    }
}
