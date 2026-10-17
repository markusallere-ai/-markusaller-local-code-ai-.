# Tests und Teststrategie

Dieses Dokument beschreibt, was die automatisierten Tests des lokalen
Kotlin-Assistenten tatsächlich absichern und was sie nicht beweisen. Die
Anzahl unten ist eine Momentaufnahme dieses Quellstands; neue Tests können
hinzukommen, ohne dieses Dokument in jeder Detailänderung anzupassen.

## Tests ausführen

Im Windows-Projektverzeichnis:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat detekt --no-daemon
.\gradlew.bat shadowJar --no-daemon
```

Die drei Aufgaben zusammen:

```powershell
.\gradlew.bat test detekt shadowJar --no-daemon
```

Auf Linux oder macOS wird `./gradlew` statt `.\gradlew.bat` verwendet. Der
JUnit-Testbericht liegt nach `test` unter
`build/reports/tests/test/index.html`. Einzelne Gruppen lassen sich gezielt
ausführen:

```powershell
.\gradlew.bat test --tests ai.CodeGeneratorTest --no-daemon
.\gradlew.bat test --tests ai.PersistenceBoundaryTest --no-daemon
.\gradlew.bat test --tests ai.IntentClassifierTest --no-daemon
```

`test` prüft Verhalten. `detekt` prüft unter anderem Kotlin-Stil und
statische Auffälligkeiten. `shadowJar` prüft, dass das ausführbare
Anwendungs-JAR gebaut werden kann. Ein erfolgreicher Lauf einer Aufgabe ist
kein Ersatz für die anderen beiden.

## Umfang in diesem Stand

Die Testsuite enthält derzeit **246 ausgeführte Testfälle** in 22
Testklassen. Manche Testmethoden prüfen zusätzlich mehrere Eingaben als
Falltabelle oder mehrere Randfälle. Die Zahl stammt aus dem JUnit-Bericht des
vollständigen Laufs und ist weder die Zahl aller Assertions noch eine
Code-Coverage-Angabe.

| Testklasse | Schwerpunkt |
|---|---|
| `ConfigurationAndNormalizationTest` | Schwellenwerte, Speicherpfade, Textfaltung, Bezeichner und JSON-Zahlen |
| `CodeGeneratorTest` | Feldextraktion, Namen, Parameter, mathematische Vorlagen und typkonsistente erzeugte Tests |
| `IntentClassifierTest` | Regeln, Prioritäten, Smalltalk- und Rückfrageerkennung sowie statistischer Fallback |
| `SupervisedIntentClassifierTest` | TF-IDF-Konfidenz- und Margin-Schwellen einschließlich Grenzwerten |
| `LanguageModelBoundaryTest` | Leere Korpora, ungültige Trainingsparameter, Gradientenkontrolle und Generierungsgrenzen |
| `LanguageModelPersistenceTest` | JSON-Fehler, Tensorformvalidierung, Gewichtsbereinigung und gespeicherte Modellfelder |
| `PersistenceBoundaryTest` | Validierung, normalisierte Prompt-Ersetzung, beschädigte JSONL-Zeilen, Historiengrenzen und Migration |
| `ReliabilityRegressionTest` | Gesprächskontext, Smalltalk, Lernbestätigung, Korrekturen, Verlauf und konkurrierende Schreibvorgänge |
| `TokenizerStateValidationTest` | Snapshot-Version, Merge-Limits, Vokabular-IDs, normalisierte Duplikate und Vektorformen |
| `CodeAssistantTokenizerTest` | Korpusvielfalt, Tokenisierung, Sortierreihenfolge, Eingabegrenzen, Speicherort und Textfortsetzung |
| `EmbeddingRegressionTest` | Embedding-Persistenz, Skip-Gram-Training und belastbare Tokenizer-Wiederherstellung |
| `ExampleSnippetGeneratorTest` | Pi-Genauigkeitsgrenzen und wichtige Pi-/Fibonacci-Eigenschaften |
| `CodeAssistantBugFixTest` | Wissensabruf, Assistant-API, Feedforward-Modell-Persistenz und Fehlerregressionen |
| `EvaluationTest` | Metriken, Datenaufteilung, Reproduzierbarkeit und Diagnosetext |
| `EvaluationBoundaryTest` | Mindestdaten, nicht endliche Werte und Loss-Berichtsränder |
| `GeneratedSourceCompilationTest` | Kompilierung aller 40 Code-Trainingsprompts, spezialisierter Vorlagen und Temperatur-/Dezimalfälle |
| `TinyTransformerChatTest` | lokale Transformer-Konvergenz, numerische Gradientenchecks für zwölf Parametergruppen, deterministische Inferenz, JSON-Roundtrip, UI-Opt-in und Chat-Integration |
| `GoogleSearchTest` | Google-URL-Kodierung, Eingabegrenzen, Browser-Aufruf, Opt-in/Datenschutzhinweis und Einstellungsfenster |
| `ResearchNotesStoreTest` | Lokales fortlaufendes Sammeln, Zeitstempel, Textgrenzen und leere Eingaben |
| `ResearchNotesSummarizerTest` | Extraktive Kurzzusammenfassungen, Satz-Deduplizierung und leere Notizen |
| `CometSearchBridgeTest` | Lokaler Übergabelink, Google-Zielvalidierung und Fallback-Seite |
| `ChessGameTest` | Schach-Trigger, Brettfarben, legale Züge, Persistenz, Stockfish-UCI und Eröffnungsdatei |

Die Schachbibliothek stellt die Zugregeln bereit. Die Tests prüfen
Anfangsstellung, ungültige Nutzerzüge, eine legale Computerantwort,
Eröffnungsbuch-Validierung, gespeicherte Partiestände, Farbpalette,
UCI-Bewertungsparser und Subprozess-Kommunikation mit einem kleinen
Testdouble. Sie benötigen keine installierte Stockfish-Binary und ersetzen
keine vollständige Schachregel-Suite oder Engine-Stärke-Evaluation.

Die Transformer-Tests prüfen sinkende Trainings-Kreuzentropie auf einem
winzigen Dialogdatensatz, deterministische Inferenz nach einem JSON-Roundtrip,
Hyperparametergrenzen und dass die Chat-API explizites lokales Training
verlangt und Ausgaben kennzeichnet. Ein numerischer Gradient-Check vergleicht
repräsentative Werte aus zwölf Parametergruppen mit zentralen Differenzen; er
prüft nicht jedes Gewicht. Die Tests belegen keine Sprachqualität auf
unbekannten Fragen; der eingebaute Beispieldatensatz ist dafür viel zu klein.

## Was die Tests konkret absichern

### Codegenerierung

`CodeGeneratorTest` prüft, dass:

- erzeugte Datenklassen `val`-Properties enthalten und Feldtrenner nicht
  versehentlich Wortteile wie `Kunde` beschädigen;
- erzeugte Klassentests passende Beispielwerte für String-, Integer-,
  Double- und Boolean-Properties sowie eine Toleranz für Double-Vergleiche
  verwenden;
- doppelte Feldnamen nicht zu mehrfachen Konstruktorparametern werden und
  Kotlin-Schlüsselwörter als Feldnamen bereinigt werden;
- Umlaute, Funktionsnamen und sichere Standardnamen erhalten bleiben;
- Imports in Testausgaben vor den Deklarationen stehen;
- Summen- und Durchschnittsbeispiele ihre tatsächlich vorhandenen Parameter
  verwenden, den Durchschnitt gegen leere Eingaben absichern und Tests
  konkrete erwartete Zahlen prüfen;
- Maximum-, Minimum-, Differenz- und Temperaturvorlagen bei passenden Prompts
  konkrete Implementierungen erzeugen; erzeugte Dezimal-MAX- und
  Dezimal-Differenztests verwenden Double-Parameter und Double-Beispielwerte;
- inkompatible Einzelfelder oder drei skalare Summenparameter nicht in einen
  Rumpf geraten, der nicht vorhandene `numbers`-/`values`-Variablen benutzt;
- eigenständige Pi- und Fibonacci-Beispiele behalten ihre Genauigkeitsgrenzen,
  Imports, Eingabevalidierung und Memoisierung;
- Sortierbeispiele verwenden aufsteigende Reihenfolge als Standard und reagieren
  auf deutsche sowie englische Hinweise für absteigende Reihenfolge.

Die Assertions in `CodeGeneratorTest` betrachten erzeugte Quelltexte und
relevante Teilausdrücke, kompilieren aber nicht jede einzelne Variante.
Deshalb beweist eine bestandene Textprüfung nicht, dass jede denkbare
Prompt-Ausgabe syntaktisch oder semantisch kompilierbar ist.

`GeneratedSourceCompilationTest` kompiliert in einem Kotlin-Compilerlauf die
Ausgaben für alle 40 Klasse-, Datenklasse-, Funktions- und Testprompts aus
`TRAINING_INTENT_CASES` sowie Pi-, Sortier-, Fibonacci- und
Kreisumfang-Beispiele. Zusätzliche Tests kompilieren beide
Temperaturumrechnungsrichtungen sowie Dezimal-MAX und Dezimal-Differenz samt
Assertions. Der Test verwendet einen gemeinsamen Assistant und gibt die Zahl
der Funktionsausgaben mit `TODO`-Stub im JUnit-Testprotokoll aus.
Kompilierbarkeit belegt weder die inhaltliche Richtigkeit noch die
Laufzeitsemantik der Snippets und deckt keine Prompts außerhalb dieses festen
Satzes ab.

### Klassifikation

`IntentClassifierTest` prüft die sechs unterstützten Code-Intents, deutsche
Begrüßungsvarianten, häufige Smalltalk- und Rückfrageformulierungen,
Begrüßungen vor konkreten Aufträgen sowie die Priorität von Tokenisierungs-
und anderen expliziten Regeln. Zusätzlich prüft es, dass der übergebene
überwachte Klassifikator nur als Fallback aufgerufen wird und dass ohne
Treffer `Unknown` mit null Konfidenz gemeldet wird. Temperaturbezeichnungen
ohne Umrechnungsaktion dürfen nicht als Funktionsauftrag gelten.

Die Falltabellen sind gezielte Regressionseingaben. Sie sind kein zufällig
gezogener oder unabhängiger deutscher Sprachdatensatz.

### Speicherung und Gesprächszuverlässigkeit

`PersistenceBoundaryTest` und `ReliabilityRegressionTest` decken unter
anderem ab:

- leere Prompt-/Antwortwerte, Tag-Bereinigung, TextFold-normalisierte
  Prompt-Identität beim Ersetzen und
  Verhalten bei ungültigen JSONL-Zeilen;
- Recall-Grenzen für leere Anfragen und nichtpositive Trefferlimits;
- JSONL-Verlauf mit mehrzeiligen Nachrichten, beschädigten Zeilen,
  abgeschnittenem Zeilenende, Legacy-Trennern und dem Eintragslimit;
- Migration erst nach Entscheidung, Ablehnung ohne Kopieren sowie Schutz
  bereits vorhandener Zieldateien;
- Rückfragen mit ausdrücklicher Bestätigung, Ablehnung, Abbruch,
  Korrekturen, leeren Turns, direkter API-Stornierung, Smalltalk während einer
  offenen Rückfrage, Restart und Löschen gespeicherten Wissens;
- parallele atomare Schreibvorgänge und Bereinigung temporärer Dateien.

Der Trainingsbeispiel-Batch wird vor dem Schreiben vollständig validiert und
mit einem Lese-/Schreibdurchlauf gespeichert. Der Test stellt sicher, dass ein
ungültiger Eintrag nicht zu einem teilweise gespeicherten Batch führt und
dass wiederholte Prompts im Batch dem letzten Wert entsprechen.

### Tokenizer, Embeddings und kleine Modelle

`CodeAssistantTokenizerTest` prüft die Ausrichtung von Token, ID und Vektor,
Normalisierung von Großschreibung und deutschen Umlauten,
Unicode-Supplementary-Zeichen, Embedding-Dimensionen, defensive Kopien,
ungültige IDs/Werte,
Skip-Gram-Trainingsparameter sowie Textfortsetzung, inklusive Grenzen für
maximale Tokenzahl und Trigrammpriorität. Der Korpustest verlangt mindestens
50 nichtleere, nach `TextFold` eindeutige Sätze und Beispiele für Kotlin/Java,
C#, C/C++, Python und JavaScript. Der Legacy-Korpus im Projektstamm wird
ebenfalls auf leere und nach `TextFold` doppelte Zeilen geprüft.

`EmbeddingRegressionTest` prüft Tokenizer-Dateien mit ungeordneten IDs,
Embedding-Persistenz und deterministisches Retraining nach veraltetem oder
beschädigtem Zustand. Skip-Gram-Fälle prüfen Satzgrenzen, Null-Epochen,
Null-Kontextfenster, Persistenz, Parameterfehler und ausgewählte
Ähnlichkeitseigenschaften.

`TokenizerStateValidationTest` prüft separat alle wichtigen Ablehnungsgründe
für gespeicherte Tokenizer-Snapshots. Nach einem ungültigen Snapshot müssen
Vokabular und Embeddings des vorherigen Zustands unverändert bleiben.

`CodeAssistantBugFixTest` prüft Feedforward-Modell-Round-Trips über JSON,
erhaltene Parameter und Vorhersagen sowie den Vokabularindex nach `copy()`.
Das sind kleine synthetische Korpora; daraus folgt keine allgemeine
Sprachmodellfähigkeit.

`LanguageModelBoundaryTest` hält zusätzlich fest, wie das experimentelle
Modell bei leerem oder zu kurzem Trainingsmaterial, null Epochen, ungültiger
Architektur, nichtpositiven oder nicht endlichen Lernraten,
wiederholten Kontexttokens und nichtpositiven Generierungslimits reagieren soll.

`LanguageModelPersistenceTest` dokumentiert, dass Gewichte und Architektur
persistiert werden, die Trainingsverlust-Historie dagegen nicht. Fehlende oder
syntaktisch beschädigte Dateien sowie Modelle mit inkonsistenten Tensorformen
liefern `null`; nicht endliche Gewichte werden vor dem Speichern in endliche
JSON-Werte umgewandelt.

### Evaluation

`EvaluationTest` prüft definierte Mindestwerte auf den fest im Projekt
hinterlegten Intent- und Out-of-Domain-Fällen, den Verlusttrend eines kleinen
Feedforward-Trainingslaufs, Umgang mit Out-of-Vocabulary-Embedding-Paaren
sowie wiederholbare Evaluation und Diagnosetext.

`EvaluationBoundaryTest` isoliert Eingabevalidierung und Aggregation: Zu wenige
bewertbare Embedding-Paare werden abgelehnt, nicht endliche Ähnlichkeiten aus
Mittelwerten ausgeschlossen und leere Feedforward-Korpora ohne Trainingsaufruf
als fehlende Loss-Messung ausgewiesen.

Diese Mindestwerte schützen vor Regressionen auf genau diesen Fällen. Sie
sind kein Beleg für Leistung auf unbekannten Nutzereingaben. Die dokumentierte
Evaluation und ihre methodischen Grenzen stehen zusätzlich in
[`EVALUATION.md`](EVALUATION.md).

## Testdaten und Seiteneffekte

Assistant-Tests verwenden temporäre Profilverzeichnisse unter dem
Betriebssystem-Temp-Verzeichnis. Sie sollen weder Wissen aus
`%USERPROFILE%\.markusaller` lesen noch dort Testdaten hinterlassen. Tests für
Dateimigration erstellen Quell- und Zielordner explizit in temporären
Verzeichnissen.

Die Tests enthalten reproduzierbare Eingaben und fixe Assertions, aber
Dateisystem- und Betriebssystemdetails können weiterhin relevant sein. Wenn
ein Test lokal fehlschlägt, zuerst den konkreten Gradle-Fehler und den
JUnit-Bericht prüfen, statt den Test durch Aufnahme in eine Detekt-Baseline
oder durch Lockerung seiner Assertion zu unterdrücken.

## Bewusste Grenzen und nächste Testlücken

- Es gibt keine instrumentierte Zeilen-/Branch-Coverage und keinen
  unabhängigen Property-based- oder Fuzz-Datensatz.
- Die generierten Trainingsprompt-Ausgaben werden kompiliert, aber nicht
  ausgeführt. Freie Nutzereingaben und Laufzeitsemantik bleiben ungeprüft.
- Die Swing-Oberfläche wird nicht automatisiert per UI-Treiber bedient. Das
  asynchrone Verhalten ist damit nicht vollständig durch Ende-zu-Ende-Tests
  abgedeckt.
- Intent- und Embedding-Evaluation verwenden kleine, gepflegte Beispielsätze.
  Änderungen an Regeln und Schwellen können daher Overfitting auf diese
  Tests erzeugen.
- Der Test für den Feedforward-Loss ist ein Stabilitätscheck eines kleinen
  lokalen Trainingslaufs, keine Sprachqualitätsmetrik.

Sinnvolle nächste Erweiterungen wären zusätzliche negative/Out-of-Domain-Fälle
außerhalb der Evaluationsbeispiele und gezielte UI-Tests für
Lade-/Bestätigungszustände. Solche Tests sollten echte gewünschte
Verhaltensverträge festlegen, nicht nur vorhandene Implementierungsdetails
wiederholen.
