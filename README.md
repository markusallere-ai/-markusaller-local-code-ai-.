# Kotlin Code-AI (Local Prototype)

Ein lokaler Kotlin-Code-Assistent mit Swing-Oberfläche, regelbasierter
Codegenerierung, einer kleinen überwachten Intent-Klassifikation und einem
bestätigungsbasierten Wissensspeicher. Die Anwendung verwendet keine externe
LLM-API. Sie ist ein transparentes Lern- und Entwicklungsprojekt, kein Ersatz
für ein großes Sprachmodell.

> **Kurz gesagt:** Die normale Antwortgenerierung basiert auf Regeln und
> Vorlagen. Gespeicherte Erklärungen können später wiedergefunden werden, aber
> dadurch werden keine Modellgewichte trainiert. Das experimentelle
> Feedforward-Modell ist nicht mit dem normalen Chat verbunden.

## Inhaltsverzeichnis

- [Schnellstart](#schnellstart)
- [Funktionen](#funktionen)
- [Nutzung und Beispiele](#nutzung-und-beispiele)
- [Architektur](#architektur)
- [Daten und Datenschutz](#daten-und-datenschutz)
- [Entwicklung und Beiträge](#entwicklung-und-beiträge)
- [Evaluation](#evaluation)
- [Bekannte Grenzen](#bekannte-grenzen)
- [Fehlerbehebung](#fehlerbehebung)
- [Lizenz](#lizenz)

## Schnellstart

### Voraussetzungen

- JDK 17 oder neuer
- Gradle Wrapper aus dem Repository (Gradle muss für den normalen Build nicht
  separat installiert werden)

### Bauen und starten

Im Projektverzeichnis:

```powershell
.\gradlew.bat shadowJar
java -jar build\libs\Markusaller.jar
```

Unter Linux oder macOS:

```bash
./gradlew shadowJar
java -jar build/libs/Markusaller.jar
```

Das Shadow-JAR bündelt die Laufzeitabhängigkeiten. Alternativ kann der
Windows-Launcher `Markusaller.bat` verwendet werden: Er sucht zuerst nach
`Markusaller.jar` neben der Batch-Datei und danach nach
`build\libs\Markusaller.jar`. Fehlt beides, weist er auf den Build-Schritt hin.

Die gestartete Anwendung öffnet ein Swing-Fenster. Es gibt derzeit keinen
interaktiven Kommandozeilen- oder Headless-Chatmodus. Die öffentliche
Kotlin-API kann von anderem Kotlin-Code aus verwendet werden.

## Funktionen

- Kotlin-Klassen, Datenklassen, Funktionen und einfache Tests anhand von
  Prompt-Regeln und Vorlagen erzeugen.
- Kurze Begrüßungen als Smalltalk behandeln und unbekannte Eingaben nicht
  fälschlich als sichere Code-Aufträge ausgeben.
- Bei unbekannten Begriffen nach einer Erklärung fragen. Gespeichert wird die
  Erklärung erst nach einer ausdrücklichen Bestätigung; gespeicherte Einträge
  können über **Wissen** angezeigt und gelöscht werden.
- Eingaben mit einem BPE-Tokenizer zerlegen und lokale Skip-Gram-Embeddings
  sowie einen kleinen TF-IDF-Intent-Klassifikator nutzen.
- Eine lokale Evaluation mit Intent-, Out-of-Domain-, Embedding- und
  Feedforward-Trainingsmessungen ausführen.

Die Klassifikation erkennt sechs Code-Intents. Smalltalk wird separat durch
Regeln behandelt und ist kein siebter Intent im überwachten Trainingssplit.

## Nutzung und Beispiele

In der GUI schreibst du eine Nachricht in das Eingabefeld und wählst **Senden**.
Die Verarbeitung läuft im Hintergrund. Erfordert eine unbekannte Eingabe
Klärung, kannst du die Bedeutung erklären und danach mit `ja` speichern, mit
`nein` verwerfen oder mit `abbrechen` den Dialog beenden. Eine neue erkannte
Code-Anfrage unterbricht die offene Rückfrage.

Der Wissensspeicher ist auch über die Kotlin-API nutzbar:

```kotlin
val assistant = CodeAssistant()

assistant.storeKnowledge(
    prompt = "Wie soll unser Benutzer gespeichert werden?",
    answer = "data class Benutzer(val name: String, val email: String)",
    tags = listOf("projektregel")
)

val matches = assistant.recallKnowledge("Wie soll unser Benutzer gespeichert werden?")
```

`learnFromCorrection` speichert eine korrigierte Antwort für einen Prompt.
Ein exakt gleicher Prompt kann direkt mit dem gespeicherten Wissen beantwortet
werden. Unscharfe Treffer werden nur bei hinreichender Ähnlichkeit,
Wortüberlappung und Eindeutigkeit verwendet; sie übersteuern keine expliziten
Code-Aufträge. Diese Schwellen sind konservative Konfigurationen und keine
kalibrierten Wahrscheinlichkeiten.

Eine Evaluation lässt sich aus Kotlin starten:

```kotlin
val report = CodeAssistant().evaluate()
println("Training intent accuracy: ${report.trainingIntentAccuracy}")
println("OOD false acceptance: ${report.outOfDomainFalseAcceptanceRate}")
println("Embedding separation: ${report.embeddingSeparation}")
```

Die vollständige Einordnung der Evaluationswerte und ihrer Grenzen steht in
[docs/EVALUATION.md](docs/EVALUATION.md).

### Begriffe

- **Brain JSON** ist ein Diagnose-Snapshot mit Tokenizer-Metadaten,
  Vokabularbeispielen, einer kleinen Embedding-Vorschau und ausgewählten
  Ähnlichkeitswerten. Er ist keine vollständige Darstellung eines
  „Gehirns“ oder eines ChatGPT-Modells. Die GUI erzeugt ihn erst beim Klick auf
  **Brain JSON**.
- **Markusaller.bat** ist ein einfacher Windows-Starter für ein bereits
  gebautes Shadow-JAR. Sie installiert keine Abhängigkeiten und baut nicht
  automatisch.
- **Headless-/CLI-Modus:** Ein eigener interaktiver Konsolenmodus ist nicht
  implementiert. Für Tests oder Integrationen kann die Kotlin-API verwendet
  werden.

## Architektur

```text
Swing-GUI
  ├─ Eingabe, Verlauf, Wissen, Evaluation und Brain-JSON
  └─ CodeAssistant (öffentliche Fassade)
       ├─ ConversationCoordinator ──> Gesprächszustand und Bestätigung
       ├─ IntentClassifier ─────────> Regeln und TF-IDF-Fallback
       ├─ KnowledgeStore ───────────> lokales JSONL-Wissen
       ├─ CodeGenerator ────────────> regelbasierte Kotlin-Vorlagen
       ├─ TokenizerProfile ─────────> Korpus, Fingerprints und Persistenz
       │    └─ BpeTokenizer ────────> Vokabular und Skip-Gram-Embeddings
       └─ EvaluationComponent / LanguageModelTrainer
```

Gemeinsame Datenmodelle liegen in `AssistantModels.kt`; der eingebettete
Tokenizer-Trainingskorpus ist in `AssistantTrainingCorpus.kt` getrennt.

Der normale Antwortweg führt nicht durch den Feedforward-Generator. Der
Tokenizer und seine Embeddings unterstützen Tokenisierung, Wissensähnlichkeit
und Diagnose. Der Trainingskorpus enthält ergänzende deutsche und englische
Programmierbegriffe, darunter Kotlin, Java, C#, C++, Python und JavaScript.
Das erweitert nur die lokalen Token-/Embedding-Beispiele; der Codegenerator
erzeugt weiterhin ausschließlich Kotlin. Das kleine Feedforward-Modell kann
separat trainiert und zur deterministischen Fortsetzung ausprobiert werden;
es ist experimentell.

## Daten und Datenschutz

Laufzeitdaten liegen standardmäßig im Nutzerprofil:

- Windows: `%USERPROFILE%\.markusaller`
- Linux/macOS: `~/.markusaller`

Dort liegen unter anderem Tokenizer- und Fingerprint-Dateien,
`knowledge.jsonl`, `training_examples.jsonl` und der Gesprächsverlauf
`history.jsonl`. Neue Verlaufseinträge werden angehängt; der Verlauf ist auf
10.000 Einträge begrenzt und wird bei Überschreitung auf 9.000 Einträge
kompaktiert. Mehrzeilige Nachrichten bleiben in JSONL als vollständige
Textfelder erhalten. Ein vorhandenes `history.txt` wird als Legacy-Format
gelesen und bei der nächsten Speicherung übernommen.

Die Anwendung kopiert vorhandene bekannte Laufzeitdateien aus dem aktuellen
Projektordner nicht stillschweigend. Werden solche Dateien beim ersten Start
gefunden, fragt die GUI, ob sie in das Profil kopiert werden sollen. Zustimmung
oder Ablehnung wird einmalig gespeichert; die Quelldateien werden nicht
verändert.

Ein `tokenizer_corpus.txt` im Projektordner wird nur als Legacy-Datei bei
bestätigter Migration übernommen. Ohne importiertes Profilkorpus nutzt die
Anwendung ihren eingebauten deutschen und englischen Standardkorpus.

Die Anwendung sendet Prompts, Verlauf und gespeichertes Wissen nicht an eine
externe LLM-API. Der Build lädt Gradle-Abhängigkeiten aus den konfigurierten
Repositories; das ist von der Laufzeit der lokalen Anwendung zu unterscheiden.

## Entwicklung und Beiträge

### Projekt prüfen

```powershell
.\gradlew.bat test detekt --no-daemon
```

Testklassen, abgedeckte Bereiche und bekannte Testlücken sind in
[docs/TESTING.md](docs/TESTING.md) dokumentiert.

JAR erstellen:

```powershell
.\gradlew.bat shadowJar --no-daemon
```

Detekt-Regeln stehen in `config/detekt.yml`; die vorhandene Baseline ist im
Detekt-Task eingebunden. Neue Befunde sollen geprüft werden, statt sie
ungeprüft zur Baseline hinzuzufügen.

Beiträge sollten Änderungen eng begrenzen, Verhalten durch Regressionstests
absichern und dokumentierte Leistungswerte nicht ohne reproduzierbaren Lauf
aktualisieren. Ein unabhängiger Datensatz darf nicht zugleich zur Anpassung
und als unabhängiger Qualitätsnachweis verwendet werden. Vor einem Beitrag
sollten mindestens Tests und Detekt erfolgreich laufen.

Das Projekt wird unregelmäßig gepflegt. Es gibt keine Zusage für regelmäßige
Updates, Support oder die Annahme von Beiträgen.

Für Screenshots der GUI ist derzeit kein Screenshot oder GIF im Repository
hinterlegt. Es wird hier bewusst kein nicht vorhandenes Bild verlinkt.

## Evaluation

Die normalen Evaluationsaufrufe messen Intent-Klassifikation,
Out-of-Domain-False-Acceptance, Embedding-Paare und den Trainingsverlust des
kleinen Feedforward-Modells. Ein sinkender Trainingsverlust zeigt nur
Anpassung an das verwendete Trainingsmaterial; er ist kein
Generalisierungsnachweis.

**Die numerischen Werte und Details des aktuellen Evaluationsstands sind
undatiert und historisch.** Es gibt in diesem Repository keinen belegbaren
Commit- oder Laufzeitstempel für diese Zahlen. Sie dürfen daher nicht als
frische Messung des aktuellen Quellstands verstanden werden. Methodik,
Datensatzaufteilung und dokumentierte historische Zahlen stehen in
[docs/EVALUATION.md](docs/EVALUATION.md).

## Bekannte Grenzen

- Die Codegenerierung ist regel- und vorlagenbasiert. Freie, offene
  Konversation auf ChatGPT-Niveau wird nicht unterstützt.
- Die gelernte Erklärung wird als lokales Prompt-Antwort-Beispiel gespeichert;
  sie trainiert weder den Intent-Klassifikator automatisch noch die
  Modellgewichte.
- Intent- und Embedding-Metriken messen kleine, handgeschriebene Datensätze.
  Sie belegen keine breite Sprachkompetenz.
- Das Feedforward-Modell arbeitet mit einem sehr kleinen Korpus und
  deterministischer Greedy-Ausgabe; es ist kein allgemeines Sprachmodell.
- Der Verlauf wird lokal gespeichert, nicht synchronisiert und nicht
  verschlüsselt.

## Fehlerbehebung

### „Unsupported class file major version“ oder Java-/Gradle-Fehler

Prüfe, dass `java -version` JDK 17 oder neuer meldet und der Build dieselbe
Java-Installation verwendet. Für den normalen Build den mitgelieferten
Wrapper (`gradlew` beziehungsweise `gradlew.bat`) statt eines beliebigen
globalen Gradle verwenden.

### Das JAR wurde nicht gefunden

Führe im Projektverzeichnis `.\gradlew.bat shadowJar` aus. Das erwartete
Ergebnis ist `build\libs\Markusaller.jar`. `Markusaller.bat` sucht zusätzlich
nach einer gleichnamigen JAR-Datei in seinem eigenen Verzeichnis.

### Tokenizer- oder Profildateien fehlen

Beim Start werden fehlende oder durch Fingerprints als veraltet erkannte
Tokenizerzustände lokal neu erstellt. Das Nutzerprofil wird unabhängig vom
Projekt-Arbeitsverzeichnis verwendet. Der Button **Brain JSON** kann einen
Diagnose-Snapshot erzeugen, ersetzt aber keine Trainings- oder
Evaluationsdaten.

## Lizenz

Der Quellcode steht unter der [MIT-Lizenz](LICENSE). Du darfst ihn unter
anderem verwenden, verändern und weitergeben, auch kommerziell. Bei einer
Weitergabe müssen der Copyright- und Lizenzhinweis erhalten bleiben. Die
Software wird ohne Gewährleistung bereitgestellt. Prüfe zusätzlich die
Lizenzen der verwendeten Drittanbieter-Abhängigkeiten.
