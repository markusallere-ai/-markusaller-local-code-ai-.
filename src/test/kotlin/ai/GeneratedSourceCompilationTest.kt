package ai

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
