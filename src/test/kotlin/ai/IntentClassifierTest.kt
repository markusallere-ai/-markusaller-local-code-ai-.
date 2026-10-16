package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Documents stable rule precedence, smalltalk boundaries, and classifier fallback behavior. */
class IntentClassifierTest {
    @Test
    fun explicitRulesRecognizeEachSupportedCodeIntent() {
        val classifier = IntentClassifier()
        val cases = listOf(
            "Erstelle eine Klasse Person" to Intent.GenerateClass,
            "Erzeuge eine Datenklasse Person" to Intent.GenerateDataClass,
            "Schreibe eine Funktion addiere" to Intent.GenerateFunction,
            "Schreibe einen Test für leere Eingaben" to Intent.GenerateTest,
            "Erkläre Kotlin-Datenklassen" to Intent.Explain,
            "Tokenisiere diesen Satz" to Intent.Tokenize
        )

        cases.forEach { (prompt, expected) ->
            assertEquals(expected, classifier.classify(prompt), prompt)
        }
    }

    @Test
    fun germanGreetingFormsAreRecognizedAsSmalltalk() {
        val classifier = IntentClassifier()
        val greetings = listOf(
            "Hallo",
            "HI!",
            "Hey",
            "Servus",
            "Moin",
            "Na?",
            "Guten Morgen",
            "Guten Tag",
            "Guten Abend"
        )

        greetings.forEach { prompt ->
            val prediction = classifier.classifyWithDetails(prompt)
            assertEquals(Intent.Smalltalk, prediction.intent, prompt)
            assertEquals("rule", prediction.method, prompt)
            assertEquals(1.0, prediction.confidence, prompt)
        }
    }

    @Test
    fun greetingAndExplicitTaskDoNotBecomeSmalltalk() {
        val classifier = IntentClassifier()
        val cases = listOf(
            "Hallo, erstelle eine Klasse Person" to Intent.GenerateClass,
            "Hi, schreibe eine Funktion addiere" to Intent.GenerateFunction,
            "Hey, erstelle einen Test" to Intent.GenerateTest,
            "Moin, erkläre eine Kotlin-Klasse" to Intent.Explain,
            "Hallo, tokenisiere diesen Text" to Intent.Tokenize
        )

        cases.forEach { (prompt, expected) ->
            assertEquals(expected, classifier.classify(prompt), prompt)
        }
    }

    @Test
    fun greetingPlusTaskNounWithoutCreationVerbStillUsesTaskIntent() {
        val classifier = IntentClassifier()

        assertEquals(Intent.GenerateClass, classifier.classify("Hallo Klasse"))
        assertEquals(Intent.GenerateFunction, classifier.classify("Hey Funktion"))
        assertEquals(Intent.GenerateTest, classifier.classify("Moin Test"))
    }

    @Test
    fun longGreetingIsNotAcceptedByTheShortSmalltalkRule() {
        val prediction = IntentClassifier().classifyWithDetails("Hallo, wie geht es dir heute?")

        assertFalse(prediction.intent == Intent.Smalltalk)
        assertFalse(prediction.method == "rule" && prediction.intent == Intent.Smalltalk)
    }

    @Test
    fun tokenizationRuleTakesPrecedenceOverExplanationWords() {
        val prediction = IntentClassifier().classifyWithDetails(
            "Erkläre und tokenisiere das Vokabular dieses Satzes"
        )

        assertEquals(Intent.Tokenize, prediction.intent)
        assertEquals("rule", prediction.method)
    }

    @Test
    fun supervisedClassifierIsUsedOnlyWhenNoExplicitRuleMatches() {
        var classifiedPrompt: String? = null
        val classifier = IntentClassifier { normalized ->
            classifiedPrompt = normalized
            IntentPrediction(Intent.GenerateFunction, 0.75, "test-classifier", topCandidate = Intent.GenerateFunction)
        }

        val prediction = classifier.classifyWithDetails("Unknown Prompt!")

        assertEquals("unknown prompt", classifiedPrompt)
        assertEquals(Intent.GenerateFunction, prediction.intent)
        assertEquals("test-classifier", prediction.method)
        assertEquals(0.75, prediction.confidence)
    }

    @Test
    fun explicitRuleBypassesSupervisedClassifier() {
        val classifier = IntentClassifier {
            error("Explicit rule should run before the supervised classifier")
        }

        assertEquals(Intent.GenerateClass, classifier.classify("Erstelle eine Klasse Person"))
    }

    @Test
    fun missingSupervisedPredictionProducesUnknownWithZeroConfidence() {
        val prediction = IntentClassifier().classifyWithDetails("qzxv 123")

        assertEquals(Intent.Unknown, prediction.intent)
        assertEquals(0.0, prediction.confidence)
        assertEquals("none", prediction.method)
    }

    @Test
    fun explicitRulePredictionContainsItsOwnTopCandidate() {
        val prediction = IntentClassifier().classifyWithDetails("Tokenisiere einen Text")

        assertEquals(Intent.Tokenize, prediction.topCandidate)
        assertEquals(null, prediction.similarity)
        assertEquals(null, prediction.margin)
    }
}
