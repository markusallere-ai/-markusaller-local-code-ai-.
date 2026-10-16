package ai

/** Ein fester, gelabelter Testfall für die Intent-Klassifikation. */
data class IntentEvaluationCase(val prompt: String, val expected: Intent)

/** Ein Embedding-Testpaar; verwandte Wörter sollten höhere Ähnlichkeit als unverwandte haben. */
data class EmbeddingEvaluationPair(val left: String, val right: String, val related: Boolean)

/** Einzelresultat eines Klassifikationsfalls samt erwartetem Intent und Vorhersage. */
data class IntentEvaluationResult(
    val prompt: String,
    val expected: Intent,
    val prediction: IntentPrediction
) {
    /** Erkanntes Label als Kurzform für Berichte und Assertions. */
    val actual: Intent get() = prediction.intent
    /** Vergleicht das erkannte Label mit dem erwarteten Intent. */
    val correct: Boolean get() = actual == expected
}

/** Ergebnis einer Ähnlichkeitsmessung samt erwarteter Verwandtschaft. */
data class EmbeddingEvaluationResult(
    val left: String, val right: String, val related: Boolean, val similarity: Double
)

/** Messwerte aus Klassifikation, Embeddings und einem tatsächlichen Feedforward-Trainingslauf. */
data class EvaluationReport(
    val intentAccuracy: Double,
    val generalizationIntentAccuracy: Double,
    val embeddingRelatedMean: Double,
    val embeddingUnrelatedMean: Double,
    val embeddingSeparation: Double,
    val validRelatedEmbeddingCount: Int,
    val validUnrelatedEmbeddingCount: Int,
    val initialLoss: Double?,
    val finalLoss: Double?,
    val finalLossImprovement: Double?,
    val bestLossImprovement: Double?,
    val intentResults: List<IntentEvaluationResult>,
    val leaveOneOutIntentResults: List<IntentEvaluationResult>,
    val generalizationIntentResults: List<IntentEvaluationResult>,
    val outOfDomainIntentResults: List<IntentEvaluationResult>,
    val embeddingResults: List<EmbeddingEvaluationResult>,
    val lossHistory: List<Double>
) {
    /** Klar benannter Alias; die Messung verwendet die Trainingsfälle des Klassifikators. */
    val trainingIntentAccuracy: Double get() = intentAccuracy

    /** Alias für die Trainingsmessungen, damit die Berichtsfelder klar benannt bleiben. */
    val trainingIntentResults: List<IntentEvaluationResult> get() = intentResults
    /** Genauigkeit nur unter den Fällen, die Leave-one-out nicht als Unknown verworfen hat. */
    val leaveOneOutIntentAccuracy: Double
        get() {
            val accepted = leaveOneOutIntentResults.filter { it.actual != Intent.Unknown }
            return if (accepted.isEmpty()) 0.0 else accepted.count { it.correct }.toDouble() / accepted.size
        }
    /** Anteil der Leave-one-out-Beispiele, die überhaupt klassifiziert wurden. */
    val leaveOneOutCoverage: Double
        get() = if (leaveOneOutIntentResults.isEmpty()) 0.0
        else leaveOneOutIntentResults.count { it.actual != Intent.Unknown }.toDouble() / leaveOneOutIntentResults.size
    /** Alias für den festgelegten Validierungs-/Generalisierungssplit. */
    val validationIntentResults: List<IntentEvaluationResult> get() = generalizationIntentResults
    /** Alias für die Genauigkeit auf dem Validierungssplit. */
    val validationIntentAccuracy: Double get() = generalizationIntentAccuracy
    /** Anteil fachfremder Prompts, die fälschlich als bekannter Intent akzeptiert werden. */
    val outOfDomainFalseAcceptanceRate: Double
        get() = if (outOfDomainIntentResults.isEmpty()) 0.0
        else outOfDomainIntentResults.count { it.actual != Intent.Unknown }.toDouble() / outOfDomainIntentResults.size
    /** Anzahl verwandter Paare, die wegen unbekannter Wörter nicht bewertet werden konnten. */
    val excludedRelatedEmbeddingCount: Int
        get() = embeddingResults.count { it.related && !it.similarity.isFinite() }
    /** Anzahl unverwandter Paare, die wegen unbekannter Wörter nicht bewertet werden konnten. */
    val excludedUnrelatedEmbeddingCount: Int
        get() = embeddingResults.count { !it.related && !it.similarity.isFinite() }
    /** Zählt erwartete und vorhergesagte Intents für jede Zeile der Validierung. */
    val validationConfusionMatrix: Map<Intent, Map<Intent, Int>>
        get() = generalizationIntentResults.groupBy { it.expected }.mapValues { (_, rows) ->
            rows.groupingBy { it.actual }.eachCount()
        }

    /** Gibt an, ob der Loss zwischen erster und letzter Epoche insgesamt gesunken ist. */
    val feedforwardLossDecreased: Boolean
        get() = finalLossImprovement?.let { it > 0.0 } ?: false
}

/** Reproduzierbare lokale Evaluation über Trainings-, Leave-one-out- und Validierungssplits. */
class EvaluationComponent(
    private val classify: (String) -> IntentPrediction,
    private val similarity: (String, String) -> Double,
    private val train: (List<String>) -> FeedforwardLanguageModel,
    private val leaveOneOut: () -> List<IntentEvaluationResult> = { emptyList() }
) {
    /**
     * Führt feste Intent-, Out-of-Domain-, Embedding- und Modellmessungen aus.
     * Nicht endliche Similarities werden aus Mittelwerten ausgeschlossen.
     */
    fun evaluate(
        intentCases: List<IntentEvaluationCase> = TRAINING_INTENT_CASES,
        embeddingPairs: List<EmbeddingEvaluationPair> = DEFAULT_EMBEDDING_PAIRS,
        feedforwardCorpus: List<String> = DEFAULT_FEEDFORWARD_CORPUS
    ): EvaluationReport {
        require(intentCases.isNotEmpty()) { "Mindestens ein Intent-Testfall ist erforderlich." }
        require(embeddingPairs.isNotEmpty()) { "Mindestens ein Embedding-Paar ist erforderlich." }
        val intentResults = intentCases.map {
            IntentEvaluationResult(it.prompt, it.expected, classify(it.prompt))
        }
        val generalizationIntentResults = DEFAULT_GENERALIZATION_INTENT_CASES.map {
            IntentEvaluationResult(it.prompt, it.expected, classify(it.prompt))
        }
        val outOfDomainIntentResults = OUT_OF_DOMAIN_INTENT_CASES.map {
            IntentEvaluationResult(it.prompt, it.expected, classify(it.prompt))
        }
        val embeddingResults = embeddingPairs.map {
            EmbeddingEvaluationResult(it.left, it.right, it.related, similarity(it.left, it.right))
        }
        val related = embeddingResults.filter { it.related }.map { it.similarity }
        val validRelated = related.filter(Double::isFinite)
        val unrelated = embeddingResults.filterNot { it.related }.map { it.similarity }
        val validUnrelated = unrelated.filter(Double::isFinite)
        require(validRelated.size >= 3) {
            "Mindestens drei verwandte Embedding-Paare müssen im Vokabular liegen; gefunden: ${validRelated.size}."
        }
        require(validUnrelated.size >= 3) {
            "Mindestens drei unverwandte Embedding-Paare müssen im Vokabular liegen; gefunden: ${validUnrelated.size}."
        }
        val model = if (feedforwardCorpus.isEmpty()) null else train(feedforwardCorpus)
        val history = model?.lossHistory?.toList() ?: emptyList()
        val initial = history.firstOrNull()
        val final = history.lastOrNull()
        return EvaluationReport(
            intentResults.count { it.correct }.toDouble() / intentResults.size,
            generalizationIntentResults.count { it.correct }.toDouble() / generalizationIntentResults.size,
            validRelated.average(), validUnrelated.average(),
            validRelated.average() - validUnrelated.average(),
            validRelated.size, validUnrelated.size,
            initial,
            final,
            if (initial != null && final != null) initial - final else null,
            if (initial != null) initial - (history.drop(1).minOrNull() ?: initial) else null,
            intentResults,
            leaveOneOut(),
            generalizationIntentResults,
            outOfDomainIntentResults,
            embeddingResults,
            history
        )
    }

    companion object {
        val TRAINING_INTENT_CASES = listOf(
            IntentEvaluationCase("Erstelle eine Klasse Person", Intent.GenerateClass),
            IntentEvaluationCase("Lege eine Klasse fuer Fahrzeuge an", Intent.GenerateClass),
            IntentEvaluationCase("Modelliere einen Lagerartikel", Intent.GenerateClass),
            IntentEvaluationCase("Ich brauche ein Objekt fuer einen Kalender", Intent.GenerateClass),
            IntentEvaluationCase("Entwirf eine Struktur fuer Messwerte", Intent.GenerateClass),
            IntentEvaluationCase("Baue ein Modell fuer eine Bibliothek", Intent.GenerateClass),
            IntentEvaluationCase("Erzeuge eine Vorlage fuer einen Vertrag", Intent.GenerateClass),
            IntentEvaluationCase("Erzeuge ein Klassenmodell fuer einen Auftrag mit Eigenschaften", Intent.GenerateClass),
            IntentEvaluationCase("Definiere einen Typ fuer eine Spielfigur", Intent.GenerateClass),
            IntentEvaluationCase("Gestalte ein Grundgeruest fuer eine Buchung", Intent.GenerateClass),
            IntentEvaluationCase("Erstelle eine Datenklasse Benutzer", Intent.GenerateDataClass),
            IntentEvaluationCase("Lege ein Datenmodell mit Name und Punktestand an", Intent.GenerateDataClass),
            IntentEvaluationCase("Ich brauche eine Datenstruktur fuer ein Produkt", Intent.GenerateDataClass),
            IntentEvaluationCase("Erzeuge einen Datensatz fuer einen Kunden", Intent.GenerateDataClass),
            IntentEvaluationCase("Erstelle einen Container fuer Koordinaten", Intent.GenerateDataClass),
            IntentEvaluationCase("Speichere die Angaben eines Spiels in einem Typ", Intent.GenerateDataClass),
            IntentEvaluationCase("Ein Objekt soll Vorname und Alter enthalten", Intent.GenerateDataClass),
            IntentEvaluationCase("Lege eine Struktur fuer Benutzerdaten an", Intent.GenerateDataClass),
            IntentEvaluationCase("Bündele Adresse und Postleitzahl in einem Datensatz", Intent.GenerateDataClass),
            IntentEvaluationCase("Erzeuge einen Eintrag mit Titel und Erscheinungsjahr", Intent.GenerateDataClass),
            IntentEvaluationCase("Schreibe eine Funktion zur Summe", Intent.GenerateFunction),
            IntentEvaluationCase("Addiere zwei Werte in einer wiederverwendbaren Berechnung", Intent.GenerateFunction),
            IntentEvaluationCase("Sortiere eine Liste mit einer Methode", Intent.GenerateFunction),
            IntentEvaluationCase("Erzeuge eine Routine zum Umwandeln von Temperaturen", Intent.GenerateFunction),
            IntentEvaluationCase("Berechne den Preis inklusive Steuer", Intent.GenerateFunction),
            IntentEvaluationCase("Eine Operation soll den groessten Wert liefern", Intent.GenerateFunction),
            IntentEvaluationCase("Schreibe einen Ablauf zum Filtern von Namen", Intent.GenerateFunction),
            IntentEvaluationCase("Implementiere eine Umrechnung von Minuten in Sekunden", Intent.GenerateFunction),
            IntentEvaluationCase("Verfasse eine Berechnung fuer den Endbetrag", Intent.GenerateFunction),
            IntentEvaluationCase("Programmiere das Zusammenfuehren zweier Listen", Intent.GenerateFunction),
            IntentEvaluationCase("Erstelle einen Test fuer die Klasse", Intent.GenerateTest),
            IntentEvaluationCase("Pruefe ob die Berechnung den erwarteten Wert liefert", Intent.GenerateTest),
            IntentEvaluationCase("Schreibe einen Test fuer leere Eingaben", Intent.GenerateTest),
            IntentEvaluationCase("Kontrolliere das Verhalten bei ungueltigen Daten", Intent.GenerateTest),
            IntentEvaluationCase("Stelle sicher dass die Liste sortiert wird", Intent.GenerateTest),
            IntentEvaluationCase("Vergleiche das Ergebnis mit dem Sollwert", Intent.GenerateTest),
            IntentEvaluationCase("Schreibe einen Test fuer verschiedene Faelle", Intent.GenerateTest),
            IntentEvaluationCase("Validiere die Rueckgabe der Umrechnung", Intent.GenerateTest),
            IntentEvaluationCase("Finde einen Fehler beim Ueberschreiten des Grenzwerts", Intent.GenerateTest),
            IntentEvaluationCase("Verifiziere die Reaktion auf fehlende Angaben", Intent.GenerateTest),
            IntentEvaluationCase("Warum ist Kotlin statisch typisiert?", Intent.Explain),
            IntentEvaluationCase("Erklaere mir diesen Kotlin-Code", Intent.Explain),
            IntentEvaluationCase("Beschreibe den Ablauf der Methode", Intent.Explain),
            IntentEvaluationCase("Was bedeutet diese Fehlermeldung?", Intent.Explain),
            IntentEvaluationCase("Wie funktioniert die Vererbung hier?", Intent.Explain),
            IntentEvaluationCase("Weshalb wird dieser Wert veraendert?", Intent.Explain),
            IntentEvaluationCase("Gib mir den Hintergrund zu diesem Entwurf", Intent.Explain),
            IntentEvaluationCase("Ich moechte den Zusammenhang verstehen", Intent.Explain),
            IntentEvaluationCase("Fuehre die einzelnen Schritte genauer aus", Intent.Explain),
            IntentEvaluationCase("Kannst du die Ursache dafuer nennen?", Intent.Explain),
            IntentEvaluationCase("Tokenisiere diesen Text", Intent.Tokenize),
            IntentEvaluationCase("Zerlege den Satz in einzelne Tokens", Intent.Tokenize),
            IntentEvaluationCase("Teile die Eingabe in Subwoerter auf", Intent.Tokenize),
            IntentEvaluationCase("Zeige mir die Bestandteile des Textes", Intent.Tokenize),
            IntentEvaluationCase("Wandle die Nachricht in Token-IDs um", Intent.Tokenize),
            IntentEvaluationCase("Wie sieht das Vokabular fuer diesen Satz aus?", Intent.Tokenize),
            IntentEvaluationCase("Spalte den Absatz in sprachliche Einheiten", Intent.Tokenize),
            IntentEvaluationCase("Fuehre die Textsegmentierung aus", Intent.Tokenize),
            IntentEvaluationCase("Zerlege diese Woerter in ihre kleineren Stuecke", Intent.Tokenize),
            IntentEvaluationCase("Extrahiere die sprachlichen Bausteine der Zeile", Intent.Tokenize)
        )
        val DEFAULT_INTENT_CASES = TRAINING_INTENT_CASES
        val VALIDATION_INTENT_CASES = listOf(
            IntentEvaluationCase("Forme eine neue Vorlage fuer ein Theaterstueck", Intent.GenerateClass),
            IntentEvaluationCase("Richte einen Datentyp fuer einen Spielstand ein", Intent.GenerateDataClass),
            IntentEvaluationCase("Lass zwei Laengen miteinander verrechnen", Intent.GenerateFunction),
            IntentEvaluationCase("Pruefe ob ein leerer Warenkorb akzeptiert wird", Intent.GenerateTest),
            IntentEvaluationCase("Was steckt hinter dieser Entscheidung", Intent.Explain),
            IntentEvaluationCase("Mache die sprachlichen Einheiten der Nachricht sichtbar", Intent.Tokenize),
            IntentEvaluationCase("Skizziere eine Form fuer ein Musikinstrument", Intent.GenerateClass),
            IntentEvaluationCase("Erklaere den Zweck dieses Abschnitts", Intent.Explain),
            IntentEvaluationCase("Vergleiche den Wert mit der Vorgabe", Intent.GenerateTest),
            IntentEvaluationCase("Zerlege den Absatz in kleine Teile", Intent.Tokenize),
            IntentEvaluationCase("Ordne Informationen zu einem Inventargegenstand", Intent.GenerateDataClass),
            IntentEvaluationCase("Erzeuge einen Ablauf zum Ermitteln eines Durchschnitts", Intent.GenerateFunction)
        )
        val DEFAULT_GENERALIZATION_INTENT_CASES = VALIDATION_INTENT_CASES
        val OUT_OF_DOMAIN_INTENT_CASES = listOf(
            IntentEvaluationCase("asdkfj qwertz", Intent.Unknown),
            IntentEvaluationCase("Wie ist das Wetter heute?", Intent.Unknown),
            IntentEvaluationCase("Erzaehl mir einen Witz", Intent.Unknown),
            IntentEvaluationCase("Ich koche heute eine Tomatensuppe", Intent.Unknown),
            IntentEvaluationCase("Welche Zuege fahren nach Berlin?", Intent.Unknown),
            IntentEvaluationCase("Meine Lieblingsfarbe ist blau", Intent.Unknown),
            IntentEvaluationCase("Gib mir ein Rezept fuer Pfannkuchen", Intent.Unknown),
            IntentEvaluationCase("Wann beginnt das Fussballspiel?", Intent.Unknown)
        )
        val FINAL_TEST_INTENT_CASES = listOf(
            IntentEvaluationCase("Lege das Grundgeruest fuer einen Leuchtturm an", Intent.GenerateClass),
            IntentEvaluationCase("Erzeuge eine Beschreibung fuer ein Musikalbum", Intent.GenerateDataClass),
            IntentEvaluationCase("Schreibe eine Berechnung fuer die Fahrtdauer", Intent.GenerateFunction),
            IntentEvaluationCase("Pruefe ob die Anmeldung mit falschen Daten scheitert", Intent.GenerateTest),
            IntentEvaluationCase("Kannst du den Zweck dieses Abschnitts erklaeren", Intent.Explain),
            IntentEvaluationCase("Wandle den Text in seine kleinsten Bestandteile um", Intent.Tokenize),
            IntentEvaluationCase("Entwirf eine Vorlage fuer einen Wochenplan", Intent.GenerateClass),
            IntentEvaluationCase("Speichere die Angaben zu einer Lieferung in einem Datentyp", Intent.GenerateDataClass),
            IntentEvaluationCase("Programmiere eine Routine zum Berechnen des Umfangs", Intent.GenerateFunction),
            IntentEvaluationCase("Sichere ab dass eine ungueltige Kennung abgelehnt wird", Intent.GenerateTest),
            IntentEvaluationCase("Was fuehrt zu diesem Ergebnis", Intent.Explain),
            IntentEvaluationCase("Zeige die einzelnen Textbausteine dieses Absatzes", Intent.Tokenize)
        )
        val DEFAULT_EMBEDDING_PAIRS = listOf(
            EmbeddingEvaluationPair("funktion", "methode", true),
            EmbeddingEvaluationPair("klasse", "objekt", true),
            EmbeddingEvaluationPair("datenklasse", "struktur", true),
            EmbeddingEvaluationPair("tokenisierung", "text", true),
            EmbeddingEvaluationPair("modell", "struktur", true),
            EmbeddingEvaluationPair("parameter", "funktion", true),
            EmbeddingEvaluationPair("funktion", "sprache", false),
            EmbeddingEvaluationPair("klasse", "lernen", false),
            EmbeddingEvaluationPair("datenklasse", "sprache", false),
            EmbeddingEvaluationPair("tokenisierung", "lernen", false),
            EmbeddingEvaluationPair("methode", "sprache", false),
            EmbeddingEvaluationPair("parameter", "lernen", false)
        )
        val DEFAULT_FEEDFORWARD_CORPUS = listOf(
            "klasse und objekt", "klasse und funktion", "objekt und methode",
            "funktion und methode", "methode und klasse"
        )
    }
}
