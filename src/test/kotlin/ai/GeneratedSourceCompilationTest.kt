package ai

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/** Compiles all fixed evaluation code prompts and representative specialized outputs. */
class GeneratedSourceCompilationTest {
    @Test
    fun evaluationCodePromptsAndSpecializedTemplatesCompile() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("generated-source-assistant")))
        val codeIntents = setOf(
            Intent.GenerateClass,
            Intent.GenerateDataClass,
            Intent.GenerateFunction,
            Intent.GenerateTest
        )
        val evaluationCases = EvaluationComponent.TRAINING_INTENT_CASES.filter { it.expected in codeIntents }
        val functionCases = evaluationCases.filter { it.expected == Intent.GenerateFunction }
        val generated = evaluationCases.map { case ->
            case.expected to assistant.handlePrompt(case.prompt)
        }
        val functionStubs = generated.count { (intent, source) ->
            intent == Intent.GenerateFunction && source.contains("TODO")
        }
        println("Function TODO stubs: $functionStubs/${functionCases.size}")

        val specializedPrompts = listOf(
            "Berechne pi",
            "Sortiere Zahlen absteigend",
            "Fibonacci berechnen",
            "Berechne den Umfang mit Pi"
        )
        val specializedOutputs = specializedPrompts.map { prompt ->
            Intent.GenerateFunction to assistant.handlePrompt(prompt)
        }
        val allSources = generated + specializedOutputs

        assertEquals(40, evaluationCases.size)
        assertEquals(10, functionCases.size)
        compileSources(allSources.mapIndexed { index, (_, source) -> index to source })
    }

    @Test
    fun generatedTemperatureFunctionsAndTestsCompileInBothDirections() {
        val storage = Files.createTempDirectory("temperature-source-assistant")
        val assistant = CodeAssistant(AssistantConfig(storage))
        val prompts = listOf(
            "Rechne 25 Celsius in Fahrenheit um" to
                "Erstelle einen Test für eine Funktion namens celsiusToFahrenheit, " +
                    "die Celsius in Fahrenheit umrechnen soll",
            "Rechne 77 Fahrenheit in Celsius um" to
                "Erstelle einen Test für eine Funktion namens fahrenheitToCelsius, " +
                    "die Fahrenheit in Celsius umrechnen soll"
        )
        val sources = prompts.flatMapIndexed { index, (functionPrompt, testPrompt) ->
            listOf(assistant.handlePrompt(functionPrompt), assistant.handlePrompt(testPrompt))
                .mapIndexed { offset, source -> index * 2 + offset to source }
        }

        assertTrue(
            sources[1].second.contains("assertEquals(77.0, celsiusToFahrenheit(25.0), 0.0001)")
        )
        assertTrue(
            sources[3].second.contains("assertEquals(25.0, fahrenheitToCelsius(77.0), 0.0001)")
        )
        compileSources(sources)
    }

    @Test
    fun generatedDecimalMaximumFunctionAndTestCompileTogether() {
        val storage = Files.createTempDirectory("decimal-maximum-source-assistant")
        val assistant = CodeAssistant(AssistantConfig(storage))
        val source = assistant.handlePrompt(
            "Erstelle einen Test für eine Funktion, die den größten Preis in einer Liste findet"
        )

        assertTrue(source.contains("numbers: List<Double>"))
        assertTrue(source.contains("assertEquals(8.0, berechneMaximum(listOf(3.0, 8.0, 1.0)))"))
        compileSources(listOf(0 to source))
    }

    @Test
    fun generatedDecimalDifferenceFunctionAndTestCompileTogether() {
        val storage = Files.createTempDirectory("decimal-difference-source-assistant")
        val assistant = CodeAssistant(AssistantConfig(storage))
        val source = assistant.handlePrompt(
            "Erstelle einen Test für eine Funktion, die die Differenz zweier Preise berechnet"
        )

        assertTrue(source.contains("firstPrice: Double"))
        assertTrue(source.contains("assertEquals(2.0, berechneDifferenz(5.0, 3.0))"))
        compileSources(listOf(0 to source))
    }

    private fun compileSources(sources: List<Pair<Int, String>>) {
        val directory = Files.createTempDirectory("generated-kotlin-suite")
        val outputDirectory = Files.createDirectories(directory.resolve("classes"))
        val sourceFiles = sources.map { (index, source) ->
            val sourceFile = directory.resolve("Generated$index.kt")
            Files.writeString(sourceFile, "package generated.case$index\n\n$source")
            sourceFile
        }
        val compilerClasspath = listOf(
            System.getProperty("java.class.path"),
            classpathLocation(Unit::class.java),
            classpathLocation(Test::class.java)
        ).filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)
        val compilerOutput = ByteArrayOutputStream()
        val arguments = listOf(
            "-no-stdlib",
            "-no-reflect",
            "-classpath",
            compilerClasspath,
            "-d",
            outputDirectory.toString()
        ) + sourceFiles.map { it.toString() }

        val exitCode = PrintStream(compilerOutput, true, Charsets.UTF_8).use { output ->
            K2JVMCompiler().exec(output, *arguments.toTypedArray())
        }

        assertEquals(ExitCode.OK, exitCode, compilerOutput.toString(Charsets.UTF_8))
    }

    private fun classpathLocation(type: Class<*>): String =
        File(type.protectionDomain.codeSource.location.toURI()).absolutePath
}
