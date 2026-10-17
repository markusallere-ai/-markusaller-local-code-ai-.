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
.\start.bat
```

Unter Linux oder macOS:

```bash
./gradlew shadowJar
java -jar build/libs/Markusaller.jar
```

`start.bat` baut bei jedem Aufruf das aktuelle Projekt mit dem Gradle-Wrapper,
aktualisiert `Markusaller.jar` direkt im Projektordner und startet genau diese
Datei. Damit wird nicht versehentlich eine alte JAR-Kopie gestartet.
`Markusaller.bat` ruft denselben Starter auf. Ein bereits laufendes Fenster
muss vor dem Neustart geschlossen werden. Startausgaben und Fehler werden in
`build\start.log` protokolliert; kann das Swing-Fenster nicht erstellt werden,
zeigt die Anwendung eine Fehlermeldung.

Für einen aktuellen Überblick über Quellcodezeilen `Codezeilen.bat` im
Projektordner doppelklicken. Es erstellt `CODE_LINES.txt` mit Gesamtzahl,
Dateityp-Aufschlüsselung und Zeilenzahl je Datei und öffnet den Bericht.
Gezählt werden Quellcode- und Konfigurationsdateien einschließlich Leerzeilen;
Build-, Git-, IDE- und Abhängigkeitsordner bleiben außen vor.

Die gestartete Anwendung öffnet ein Swing-Fenster. Es gibt derzeit keinen
interaktiven Kommandozeilen- oder Headless-Chatmodus. Die öffentliche
Kotlin-API kann von anderem Kotlin-Code aus verwendet werden.

## Funktionen

- Kotlin-Klassen, Datenklassen, Funktionen und einfache Tests anhand von
  Prompt-Regeln und Vorlagen erzeugen.
- Häufige Begrüßungen, Dank, Abschiede und Fragen nach Fähigkeiten als
  Smalltalk beantworten.
- Im aktuellen Lauf den letzten fachlichen Gesprächsbezug für begrenzte
  Rückfragen behalten: Eine Temperaturfrage kann etwa mit „und 30?“ fortgesetzt
  werden; die letzte Antwort lässt sich wiederholen und das letzte Thema abrufen.
- Unbekannte Eingaben nicht fälschlich als sichere Code-Aufträge ausgeben.
- Bei unbekannten Begriffen nach einer Erklärung fragen. Gespeichert wird die
  Erklärung erst nach einer ausdrücklichen Bestätigung; gespeicherte Einträge
  können über **Wissen** angezeigt und gelöscht werden.
- Eingaben mit einem BPE-Tokenizer zerlegen und lokale Skip-Gram-Embeddings
  sowie einen kleinen TF-IDF-Intent-Klassifikator nutzen.
- Eine lokale Evaluation mit Intent-, Out-of-Domain-, Embedding- und
  Feedforward-Trainingsmessungen ausführen.
- Optional einen kleinen Decoder-Transformer ausschließlich lokal auf
  eingebauten Dialogbeispielen trainieren und im markierten Experimentalmodus
  ausprobieren.
- Optional eine Google-Suche im Standardbrowser öffnen. Dieser Modus ist
  standardmäßig ausgeschaltet; bei Aktivierung wird der eingegebene Suchtext an
  Google übertragen. Markusaller liest oder bedient Suchergebnisse nicht
  automatisch. Eine kleine Comet-Erweiterung kann die Suchanfrage im selben
  Tab aktualisieren.
- Kopierte Informationen in einem separaten Sammelfenster erfassen und
  fortlaufend in `~/.markusaller/research_notes.txt` speichern, ohne sie als
  Chatnachricht zu senden.
- Mit „Lass eine Runde Schach spielen“ oder „Let's play chess“ ein Schachfenster
  öffnen. Der Nutzer spielt Weiß; legale Züge und Spielende werden von Chesslib
  geprüft. Markusaller spielt Schwarz mit einer kleinen lokalen Materialsuche
  und einer dateibasierten Eröffnungsbank unter `src/main/resources/ai/`.
  Partien werden im Nutzerprofil unter `chess_games.jsonl` gespeichert. Nach
  Spielende kann eine Stockfish-Analyse Genauigkeit, Bewertungsverluste und
  Zugkategorien anzeigen. Stockfish muss separat installiert und beim ersten
  Analysieren ausgewählt werden.

Die Schach-Gegenseite ist kein trainiertes neuronales Netz und keine starke
Schachengine. Chesslib stellt Zugregeln und legale Züge bereit; die lokale
Gegenseite nutzt Eröffnungszüge und eine begrenzte Materialsuche. Die
Partieanalyse nutzt Stockfish über das UCI-Protokoll; Stockfish ist nicht im
Projekt-JAR enthalten. Genauigkeit und Zugkategorien sind Näherungswerte,
keine offiziellen Chess.com-Wertungen. Eine Elo-Zahl lässt sich aus einer
einzelnen Partie nicht seriös bestimmen.

Die Klassifikation erkennt sechs Code-Intents. Smalltalk wird separat durch
Regeln behandelt und ist kein siebter Intent im überwachten Trainingssplit.

## Nutzung und Beispiele

In der GUI schreibst du eine Nachricht in das Eingabefeld und wählst **Senden**.
Die Verarbeitung läuft im Hintergrund. Erfordert eine unbekannte Eingabe
Klärung, kannst du die Bedeutung erklären und danach mit `ja` speichern, mit
`nein` verwerfen oder mit `abbrechen` den Dialog beenden. Eine neue erkannte
Code-Anfrage unterbricht die offene Rückfrage.

Der Assistent kann außerdem einfache Gesprächsschritte beantworten, etwa
„Wie geht es dir?“, „Was kannst du?“ und „Danke“. Innerhalb der laufenden
Sitzung versteht er begrenzte Rückbezüge wie „Wiederhole das“ und „Worüber
haben wir gesprochen?“. Bei Temperaturfragen kann ein neuer Zahlenwert die
zuvor genannte Zieleinheit übernehmen, zum Beispiel „Was sind 20 Grad in
Fahrenheit?“ → „und 30?“.

Der erweiterte Beispielsatzbestand enthält nun zusätzlich deutsche
Alltags- und Dialogformulierungen. Diese Beispiele stehen für die begrenzten,
korpusgestützten Textfortsetzungen zur Verfügung; sie trainieren keine freien
Chat-Antworten. Die etablierten Standard-BPE-Merges und Embeddings bleiben
bewusst stabil, damit der größere Dialogkorpus den vorhandenen
Wissensabruf nicht verschlechtert. Dialogantworten und Rückbezüge sind
gezielte Regeln. Markusaller bleibt ein lokaler Assistent ohne externe
Sprachmodell-API und ohne allgemeines Weltwissen. Die optionale Google-Suche ist
ausdrücklich aktivierbare Ausnahme vom lokalen Betrieb. **Zurücksetzen**
löscht den flüchtigen Gesprächskontext.

### Experimenteller Transformer-Chat

Aktiviere **Experimenteller Transformer-Chat** und wähle **KI-Modell
trainieren**. Das Training läuft lokal im Hintergrund; danach kann der
Experimentalmodus für Nachrichten verwendet werden. Er nutzt einen kleinen
Decoder mit einer kausalen Single-Head-Attention, trainierbaren
Q/K/V-Projektionen, Positions-Embeddings und einem Feedforward-Teil. Er lernt
Next-Token-Vorhersagen aus den fest eingebauten Dialogbeispielen,
ohne vortrainierte Gewichte oder externe Dienste. Das Modell wird unter
`transformer_chat_model.json` im lokalen Profil gespeichert. Der Verlauf der
aktuellen Sitzung wird begrenzt als Kontext übergeben; der normale
regelbasierte Modus bleibt unverändert.

Das ist ein technischer Prototyp, kein leistungsfähiger Chatbot: Das Modell
ist klein, hat nur einen Attention-Kopf, kein Layer-Normalization oder
Dropout und sehr wenig Trainingsmaterial. Mit dem sehr kleinen Datensatz lernt
das Modell vor allem, Trainingsbeispiele zu reproduzieren; es gibt keinen
Validierungssplit, und der protokollierte Verlust ist nur der Trainingsverlust.
Das ist kein belastbarer Nachweis von Sprachverständnis. Es kann grammatikalisch
oder inhaltlich falsche und wiederholende Texte erzeugen; jede Ausgabe wird
deshalb als experimentell markiert. Mehr Epochen beheben nicht den Mangel an
vielfältigen Trainingsdaten. Der Modus ist vor dem ersten Training nicht
verfügbar.

Der Transformer nutzt Wort-Token. Wörter, die beim Training nicht vorkamen,
fallen auf ein einzelnes `<unbekannt>`-Token zurück; sie werden nicht in
Subwörter zerlegt. Das begrenzt unbekannte Eingaben zusätzlich. Das Training
berechnet pro Next-Token-Ziel einen separaten Vorwärts- und Rückwärtsschritt
mit SGD. Das ist für diesen kleinen Lernprototyp bewusst einfach, aber kein
effizienter, vektorisierter Batch-Trainingspfad. Der Gradiententest vergleicht
repräsentative Parameterwerte aus zwölf Parametergruppen mit zentralen
numerischen Differenzen; er ersetzt keinen vollständigen formalen Beweis.

Hintergrund: [Attention Is All You Need](https://arxiv.org/abs/1706.03762)
beschreibt skalierte Attention und Maskierung für autoregressive Vorhersagen;
der [Annotated Transformer](https://nlp.seas.harvard.edu/annotated-transformer/)
führt die Bausteine als nachvollziehbare Implementierung aus. Die
[Hugging Face-Dokumentation zu BPE](https://huggingface.co/learn/llm-course/en/chapter6/5)
erläutert, wie Subword-Tokenisierung Wort-OOV reduzieren kann und byte-level
BPE unbekannte Zeichen abdeckt. Diese Änderungen wurden hier nicht
blind übernommen: Ein byte-level Modell würde ein anderes Vokabular,
Tokenformat und Trainingsbudget erfordern und wird deshalb nicht als
unkompatible Umstellung in bestehende Modell-Dateien eingeschleust.

### Einstellungen und optionale Google-Suche

Öffne oben rechts **Einstellungen**, um den experimentellen Transformer-Chat
oder die Google-Suche ein- und auszuschalten. Der Transformer kann im selben
Fenster über **KI-Modell trainieren** trainiert werden. Die beiden Modi schließen
sich gegenseitig aus.

Aktiviere **Google-Suche im Browser**, um jede folgende Eingabe als Suchanfrage
an Google zu senden und die Ergebnisse im Standardbrowser zu öffnen, solange
der Schalter aktiv bleibt. Er ist standardmäßig aus; die Oberfläche weist
darauf hin, dass die Anfrage den Rechner verlässt. Für öffentliche
Suchergebnisse ist in der Regel kein Google-Konto nötig, aber Google kann
regionale Hinweise oder Bestätigungsseiten anzeigen. Markusaller umgeht solche
Prüfungen nicht.

Diese Integration ist bewusst keine autonome Browsersteuerung: Markusaller
liest keine Ergebnis- oder Webseiteninhalte, klickt keine Links an und sendet
keine Formulare. Du prüfst die Ergebnisse selbst im Browser. Der Google-Modus
und der experimentelle Transformer-Modus schließen sich gegenseitig aus.

Damit Google-Suchen in Comet denselben Tab wiederverwenden, muss die optionale
Erweiterung aus `comet-extension` einmalig in Comet geladen werden:

1. In Comet `chrome://extensions` öffnen und den Entwicklermodus aktivieren.
2. **Entpackte Erweiterung laden** wählen und den Ordner `comet-extension` aus
   diesem Projekt auswählen.
3. Den Google-Suchmodus in Markusaller aktivieren. Die erste Suche wird zum
   wiederverwendeten Tab; weitere Suchen aktualisieren diesen Tab. Ein
   kurzlebiger lokaler Übergabetab kann während der Aktualisierung erscheinen
   und wird danach geschlossen.

Die Erweiterung erhält nur den Google-Suchlink, aktualisiert den zuletzt
verwendeten Markusaller-Suchtab und schließt den lokalen Übergabetab. Sie liest
keine anderen Tabs. Ohne geladene Erweiterung erscheint der lokale Übergabetab
mit einem direkten Google-Link als Fallback. Die lokale Übergabe bindet nur an
`127.0.0.1` und transportiert ausschließlich den Google-Suchlink.

Über **Infos sammeln** öffnet sich daneben ein nicht-modales Fenster. Du kannst
Text aus der Browser-Suche hineinkopieren und mit **Lokal speichern** als
separate, zeitgestempelte Notiz anhängen. Das Fenster bleibt zum fortlaufenden
Sammeln geöffnet. Im Tab **Gespeicherte Infos ansehen** kannst du die Datei
einsehen und aktualisieren. Die Notizen gehen nicht automatisch in den Chat,
werden nicht als abrufbares Wissen eingelernt und nur lokal unter
`~/.markusaller/research_notes.txt` gespeichert.

Mit **„Geh die Infos durch“** öffnet Markusaller diese gespeicherten Notizen
und erstellt einen kurzen, extraktiven Vorschlag aus den ersten passenden
Sätzen jeder Notiz. Prüfe und bearbeite ihn; erst **Als Wissen speichern**
legt ihn als abrufbaren Eintrag in `knowledge.jsonl` ab. Das trainiert keine
Modellgewichte. Es bewahrt die Originalnotizen unverändert und macht die
freigegebene Kurzfassung für den bestehenden Wissensabruf verfügbar.

Einfache numerische Temperaturfragen mit angegebener Zielskala werden direkt
berechnet, zum Beispiel **„Was sind 20 Grad in Fahrenheit?“** → **20 °C
entsprechen 68 °F.** Eine ausdrücklich angeforderte Kotlin-Funktion wird
weiterhin als Kotlin-Code erzeugt.

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
  ├─ Eingabe, Verlauf, Wissen, Evaluation, Brain-JSON und optionale Modi
  ├─ ChessGameDialog ──────────────> Chesslib-Regeln und Stockfish-Analyse
  └─ CodeAssistant (öffentliche Fassade)
       ├─ ConversationCoordinator ──> Gesprächszustand und Bestätigung
       ├─ IntentClassifier ─────────> Regeln und TF-IDF-Fallback
       ├─ KnowledgeStore ───────────> lokales JSONL-Wissen
       ├─ CodeGenerator ────────────> regelbasierte Kotlin-Vorlagen
       ├─ TinyTransformerChat ──────> optionaler experimenteller Decoder
       ├─ TokenizerProfile ─────────> Korpus, Fingerprints und Persistenz
       │    └─ BpeTokenizer ────────> Vokabular und Skip-Gram-Embeddings
       └─ EvaluationComponent / LanguageModelTrainer
```

Gemeinsame Datenmodelle liegen in `AssistantModels.kt`; der eingebettete
Tokenizer-Trainingskorpus ist in `AssistantTrainingCorpus.kt` getrennt.
Technische Recherche, bekannte Tokenizer-/Generatorgrenzen und die Quellen
stehen in [docs/CODEASSISTANT_GENERATOR_TOKENIZER_RESEARCH.txt](docs/CODEASSISTANT_GENERATOR_TOKENIZER_RESEARCH.txt).

Der normale Antwortweg führt nicht durch den Feedforward-Generator. Der
Tokenizer und seine Embeddings unterstützen Tokenisierung, Wissensähnlichkeit
und Diagnose. Der Trainingskorpus enthält ergänzende deutsche und englische
Programmierbegriffe, darunter Kotlin, Java, C#, C++, Python und JavaScript.
Der ergänzte Beispielsatzbestand umfasst nun auch Dialog-, Alltags- und
Kontextformulierungen. Der Codegenerator erzeugt weiterhin ausschließlich
Kotlin. Das kleine Feedforward-Modell kann
separat trainiert und zur deterministischen Fortsetzung ausprobiert werden;
es ist experimentell. Davon getrennt ist der optional trainierbare
`TinyTransformerChat`: Ein einzelner Decoder-Block mit kausaler Attention,
Feedforward-Schicht und lokaler Next-Token-Optimierung. Er verarbeitet
Wort-Token statt BPE-Subwörtern und liefert greedy Fortsetzungen. Er verwendet
weder extern vortrainierte Parameter noch Netzwerkdienste.

## Daten und Datenschutz

Laufzeitdaten liegen standardmäßig im Nutzerprofil:

- Windows: `%USERPROFILE%\.markusaller`
- Linux/macOS: `~/.markusaller`

Dort liegen unter anderem Tokenizer- und Fingerprint-Dateien,
`knowledge.jsonl`, `training_examples.jsonl`, `chess_games.jsonl`,
`research_notes.txt` und der Gesprächsverlauf `history.jsonl` sowie nach dem
Training `transformer_chat_model.json`. Neue
Verlaufseinträge werden angehängt; der Verlauf ist auf
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
externe LLM-API. Nur wenn **Google-Suche im Browser** eingeschaltet ist, wird
der aktuelle Suchtext an Google übertragen; Gesprächsverlauf und lokales
Wissen werden dabei nicht mitgesendet. Der Build lädt Gradle-Abhängigkeiten aus
den konfigurierten Repositories; das ist von der Laufzeit der lokalen
Anwendung zu unterscheiden.

## Entwicklung und Beiträge

### Projekt prüfen

```powershell
.\gradlew.bat test detekt --no-daemon
```

Testklassen, abgedeckte Bereiche und bekannte Testlücken sind in
[docs/TESTING.md](docs/TESTING.md) dokumentiert.

JAR erstellen:

```powershell
.\gradlew.bat runnableJar --no-daemon
```

Der Starter-Build erzeugt `Markusaller.jar` direkt im Projektordner.

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
- Die Schachanalyse setzt eine separate Stockfish-Installation voraus;
  Bewertungsverlust, Genauigkeit und Zugkategorien sind nur Näherungen.

## Fehlerbehebung

### „Unsupported class file major version“ oder Java-/Gradle-Fehler

Prüfe, dass `java -version` JDK 17 oder neuer meldet und der Build dieselbe
Java-Installation verwendet. Für den normalen Build den mitgelieferten
Wrapper (`gradlew` beziehungsweise `gradlew.bat`) statt eines beliebigen
globalen Gradle verwenden.

### Das JAR wurde nicht gefunden

Führe im Projektverzeichnis `.\start.bat` aus. Das Skript protokolliert
Buildfehler in `build\start.log` und startet nur, wenn der aktuelle Build
erfolgreich war. Das erzeugte `Markusaller.jar` liegt neben der Batchdatei.

### Stockfish-Analyse

Für Partieanalysen Stockfish separat von der offiziellen
[Stockfish-Downloadseite](https://stockfishchess.org/download/) beziehen.
Beim ersten Analysieren die Engine-Datei auswählen. Die Anwendung speichert
den Pfad lokal in den Java-Benutzereinstellungen; sie lädt oder verteilt
Stockfish nicht selbst.

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
