# Evaluation und Modellmessungen

Diese Datei hält Aufbau, Interpretation und bekannte Grenzen der lokalen
Evaluationssuite fest. Sie verhindert, dass kleine Entwicklungsmessungen in
der README wie ein allgemeiner Leistungsnachweis wirken.

## Status und Aktualität

Die in der bisherigen README dokumentierten Zahlen haben keinen belegten
Erstellungszeitpunkt oder Commit-Bezug. Sie sind deshalb als **historische,
undatierte Momentaufnahme** zu behandeln, nicht als Messung des aktuellen
Quellstands. Eine neue Evaluationsausführung muss erstellt werden, bevor diese
Werte aktualisiert oder als aktuelle Ergebnisse zitiert werden.

Die Werte wurden im damaligen Text wie folgt festgehalten:

- Intent-Trainingsfälle: 60 von 60 korrekt.
- Leave-one-out: 18 von 30 akzeptierten Vorhersagen korrekt; 30 von 60 Fälle
  wurden angenommen (50 % Coverage, 60 % selective accuracy).
- Validierung: 10 von 12 korrekt; zwei Fälle wurden als `Unknown` verworfen.
- Out-of-domain: 0 von 8 fachfremden Prompts fälschlich angenommen.
- Embedding-Separation: `0.4510` für sechs verwandte und sechs unverwandte
  Wortpaare; alle zwölf Paare lagen laut damaligem Bericht im Vokabular.
- Feedforward-Trainingsloss: `2.1209` auf `2.0367`, Verbesserung `0.0841`,
  auf einem Korpus mit fünf kurzen Sätzen.

Diese Zahlen sind weder reproduziert noch unabhängig verifiziert. Die
Validierungsfälle waren bei der Entwicklung sichtbar. Der ausgesparte
12-Fall-Testpool wurde ebenfalls in derselben Entwicklungssitzung formuliert,
hat Wortlautüberschneidungen und ist nicht blind; er ist deshalb kein
unabhängiger Qualitätsbeleg und wird nicht zur normalen Evaluation gerechnet.

## Aufruf

Aus Kotlin kann die Standardmessung so gestartet werden:

```kotlin
val report = CodeAssistant().evaluate()
println("Intent training accuracy: ${report.trainingIntentAccuracy}")
println("Generalization accuracy: ${report.generalizationIntentAccuracy}")
println("Leave-one-out accuracy: ${report.leaveOneOutIntentAccuracy}")
println("Leave-one-out coverage: ${report.leaveOneOutCoverage}")
println("Validation accuracy: ${report.validationIntentAccuracy}")
println("OOD false acceptance: ${report.outOfDomainFalseAcceptanceRate}")
println("Embedding separation: ${report.embeddingSeparation}")
println("Feedforward loss: ${report.initialLoss} -> ${report.finalLoss}")
```

Für eine textuelle Diagnose der einzelnen Fälle steht `formatEvaluation()` zur
Verfügung. Der Bericht enthält Vorhersage, erwartetes Label, Top-Kandidat,
Methode, rohe Similarity/Confidence, Margin und eine
Validierungs-Confusion-Matrix.

## Intent-Klassifikation

Sechs Intents haben jeweils zehn gelabelte Trainingsprompts:

- `GenerateClass`
- `GenerateDataClass`
- `GenerateFunction`
- `GenerateTest`
- `Explain`
- `Tokenize`

Daneben enthält der Validierungssplit zwölf Prompts (zwei je Intent). Weitere
zwölf Fälle sind im Code als `FINAL_TEST_INTENT_CASES` abgelegt, werden aber
von der normalen Evaluation nicht ausgewertet. Es existieren außerdem acht
feste Out-of-Domain-Beispiele, die als `Unknown` erwartet werden.

Der Klassifikator verwendet zunächst explizite Regeln und danach einen
überwachten TF-IDF-Nearest-Centroid-Klassifikator mit Wortmerkmalen,
Zeichen-N-Grammen und Wort-Bigrammen. Lokal gespeicherte
`TrainingExample`-Einträge mit bekanntem Intent werden zum überwachten
Trainingssatz hinzugefügt. Die zusätzliche Smalltalk-Erkennung ist
regelbasiert und kein Intent des überwachten Sechs-Klassen-Datensatzes.

Leave-one-out entfernt jeweils einen Entwicklungsfall aus dem Trainingssatz
und klassifiziert ihn erneut. Die selective accuracy berechnet die Trefferquote
nur unter angenommenen Fällen; die Coverage zeigt den Anteil angenommener
Fälle an allen Leave-one-out-Beispielen. Beide Werte müssen gemeinsam gelesen
werden: Ein Klassifikator kann die selective accuracy steigern, indem er mehr
Fälle als `Unknown` zurückweist.

Die in `AssistantConfig` gesetzten Startschwellen `similarity >= 0.05` und
`margin >= 0.04` wurden laut historischem Bericht anhand eines
Leave-one-out-Sweeps gewählt. Die Kombination ergab 18 richtige Vorhersagen
bei 30 akzeptierten Fällen; eine benachbarte Kombination `0.05/0.02` ergab
25 Treffer bei 44 akzeptierten Fällen. Diese Werte sind kleine, nicht
unabhängige Entwicklungsdaten und ergeben keine kalibrierten
Wahrscheinlichkeiten. `confidence` und `similarity` sind rohe Cosine-Werte.

## Out-of-domain-Messung

Die False-Acceptance-Rate ist der Anteil der festgelegten fachfremden Prompts,
deren tatsächliches Intent nicht `Unknown` ist. Die acht Beispiele sind ein
Regressionstest gegen bekannte Ausreißer, keine repräsentative Stichprobe
aller möglichen unbekannten Eingaben. Eine historische Rate von `0/8` sagt
nichts Sicheres über neue Formulierungen, Sprachen oder Fachgebiete aus.

Die Intent-Evaluation vergleicht ausschließlich das vorhergesagte Intent-Label
mit dem erwarteten Label. Sie prüft nicht, ob die anschließend generierte
Erklärung fachlich korrekt oder hilfreich ist. Dafür gibt es gezielte
Antworttests für ausgewählte Themen, aber noch keine unabhängige Metrik der
Antwortqualität.

## Einmalige manuelle Prompt-Stichprobe

Zusätzlich wurden 20 neu formulierte Prompts außerhalb der fest im Repository
gespeicherten Trainings- und Evaluationsfälle durch `handlePrompt` geprüft.
Die temporäre Ausführung wurde danach entfernt; die Prompts wurden nicht als
Trainingsdaten hinzugefügt. Als brauchbar zählte eine Antwort nur, wenn sie die
Aufgabe direkt und inhaltlich plausibel erfüllte, nicht bloß kompilierbaren
Text erzeugte. Nach den dabei entdeckten Korrekturen für Feldtypen, Summen mit
Preisen, Satz-Tokenisierung und Listen-/Mengen-Erklärung waren 13 von 20
Antworten brauchbar. Sechs weitere Prompts enthielten `TODO`-Platzhalter: vier
Funktionsanfragen und zwei Testanfragen. Eine weitere Ausgabe benannte einen
Parkautomaten als `Parkautomaten` statt im Singular als `Parkautomat`.
Besonders deutlich blieben Funktionsstub-Anfragen wie Rabattberechnung,
Begrüßungsausgabe und Celsius-Fahrenheit-Umrechnung sowie eine E-Mail-Prüfung
ohne Validierungslogik.

Diese kleine, vom selben Entwickler formulierte und bewertete Stichprobe ist
subjektiv und nicht blind. Sie ist ein Probleminventar, kein unabhängiger
Qualitätsnachweis. Die separate Kompilierprüfung zeigt zudem, dass 7 von 10
Funktionsausgaben der festgelegten Intent-Trainingsfälle noch TODO-Stubs
enthalten; syntaktische Gültigkeit ersetzt keine brauchbare Implementierung.

`trainingIntentAccuracy` bezeichnet ausdrücklich die Genauigkeit auf den
Trainingsfällen und ist kein Generalisierungsmaß. `intentAccuracy` bleibt als
quellkompatibler Feldname erhalten; neue Aufrufer sollten den klareren Alias
`trainingIntentAccuracy` verwenden. Für eine getrennte
Messung auf den festgelegten, aber während der Entwicklung sichtbaren
Validierungsfällen dient `generalizationIntentAccuracy` (auch als
`validationIntentAccuracy` verfügbar).

## Embeddings

Die Standardmessung enthält sechs als verwandt und sechs als unverwandt
markierte Wortpaare. OOV-Paare (Wörter außerhalb des Tokenizer-Vokabulars)
werden mit nicht endlicher Similarity markiert und aus Mittelwerten entfernt.
Damit wird eine Mindestzahl von drei gültigen Paaren je Kategorie verlangt.
Der Report enthält die gültigen Anzahlen und berechnet die Separation als
Mittelwert der verwandten Similarities minus Mittelwert der unverwandten
Similarities.

Skip-Gram trainiert pro Satz separat mit drei reproduzierbaren Negativbeispielen
für jedes positive Kontextpaar. Embeddings werden bei Bedarf deterministisch
initialisiert. Tokenizer-Vokabular und Embeddings besitzen unabhängige
SHA-256-Fingerprints; Änderungen an Korpus oder Algorithmus lösen die passende
Neuberechnung aus. Ein manuell gespeicherter Vektor bleibt erhalten, solange
sein Embedding-Fingerprint gültig ist.

Eine positive Separation bei zwölf handbeschrifteten Paaren belegt keine
allgemeine semantische Kompetenz. Das Modell versteht dadurch weder zuverlässig
Negation noch Kontext, Mehrdeutigkeit oder komplexe Bedeutungsbeziehungen.
Die Wortpaare sind außerdem nicht unabhängig vom Trainingskorpus: etwa
`funktion`/`methode`, `klasse`/`objekt`, `datenklasse`/`struktur` und
`tokenisierung`/`text` erscheinen auch in Korpussätzen. Die Separation ist
damit eine kleine Trainingskorpus-Diagnostik und kein Held-out-Test der
Embedding-Generalisierung.

## Feedforward-Modell

Das lokale Feedforward-Modell wird separat auf einem kleinen Korpus trainiert.
Sein Loss wird auf denselben Trainingsbeispielen gemessen, auf denen es lernt;
es gibt keinen getrennten Validierungs-Loss. Ein sinkender Wert misst daher
nur Trainingsanpassung und keine Generalisierung.

`finalLossImprovement` ist die Differenz zwischen erstem und letztem Loss.
`bestLossImprovement` vergleicht den ersten Loss mit dem besten beobachteten
Zwischenwert. `feedforwardLossDecreased` ist ein Trendindikator und keine
Qualitätsbewertung der generierten Sprache. Das Modell ist experimentell und
wird von `handlePrompt` nicht als Antwortgenerator verwendet.

## Nächste belastbare Evaluation

Für eine aussagekräftigere Messung sollte ein unabhängiger Prüfer neue,
vorher nicht veröffentlichte Fälle für bekannte und fachfremde Prompts
formulieren. Schwellen dürfen nicht auf diesem finalen Satz optimiert werden.
Vor dem Lauf sollten Quellstand, Datum, Korpusversion, Schwellen und
Ausführungsbefehl festgehalten werden. Trainings-, Schwellenkalibrierungs-,
Validierungs- und finaler Testsatz müssen getrennt bleiben.
