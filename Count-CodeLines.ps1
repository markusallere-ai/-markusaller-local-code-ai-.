param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$excludedDirectories = @(
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".vscode",
    "build",
    "node_modules",
    "out",
    "target"
)
$sourceExtensions = @(
    ".bat", ".c", ".cc", ".cmd", ".cpp", ".cs", ".css", ".go", ".gradle",
    ".h", ".hpp", ".html", ".java", ".js", ".jsx", ".kt", ".kts", ".mjs",
    ".cjs", ".ps1", ".py", ".rs", ".scss", ".sh", ".ts", ".tsx", ".xml",
    ".yaml", ".yml", ".json"
)

$files = [System.Collections.Generic.List[object]]::new()
$directories = [System.Collections.Generic.Stack[string]]::new()
$directories.Push($root)

while ($directories.Count -gt 0) {
    $currentDirectory = $directories.Pop()
    foreach ($directory in Get-ChildItem -LiteralPath $currentDirectory -Directory -Force) {
        if ($excludedDirectories -contains $directory.Name) {
            continue
        }
        if (($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            continue
        }
        $directories.Push($directory.FullName)
    }

    foreach ($file in Get-ChildItem -LiteralPath $currentDirectory -File -Force) {
        if ($sourceExtensions -notcontains $file.Extension.ToLowerInvariant()) {
            continue
        }

        $content = [System.IO.File]::ReadAllText($file.FullName)
        $newlineCount = [regex]::Matches($content, "`r`n|`n|`r").Count
        $endsWithNewline = $content.EndsWith("`n") -or $content.EndsWith("`r")
        $totalLines = if ($content.Length -eq 0) {
            0
        } elseif ($endsWithNewline) {
            $newlineCount
        } else {
            $newlineCount + 1
        }
        $nonEmptyLines = @(
            $content -split "`r`n|`n|`r" |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        ).Count
        $relativePath = $file.FullName.Substring($root.Length).TrimStart("\")

        $files.Add([pscustomobject]@{
            Path        = $relativePath
            Extension   = $file.Extension.ToLowerInvariant()
            TotalLines  = $totalLines
            CodeLines   = $nonEmptyLines
        })
    }
}

$sortedFiles = @($files | Sort-Object Path)
$totalLines = ($sortedFiles | Measure-Object -Property TotalLines -Sum).Sum
$totalCodeLines = ($sortedFiles | Measure-Object -Property CodeLines -Sum).Sum
if ($null -eq $totalLines) {
    $totalLines = 0
    $totalCodeLines = 0
}

$report = [System.Collections.Generic.List[string]]::new()
$report.Add("Codezeilen-Bericht")
$report.Add("Projekt: $root")
$report.Add("Erstellt: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$report.Add("")
$report.Add("Dateien: $($sortedFiles.Count)")
$report.Add("Zeilen insgesamt (einschliesslich Leerzeilen): $totalLines")
$report.Add("Nicht-leere Quelltextzeilen: $totalCodeLines")
$report.Add("")
$report.Add("Aufschluesselung nach Dateityp (Zeilen insgesamt / nicht-leere Zeilen)")
$report.Add("---------------------------------------------------------------")
foreach ($group in ($sortedFiles | Group-Object Extension | Sort-Object Name)) {
    $extensionTotals = $group.Group | Measure-Object -Property TotalLines -Sum
    $extensionCodeTotals = $group.Group | Measure-Object -Property CodeLines -Sum
    $report.Add(("{0,-10} {1,8} / {2,-8} ({3} Dateien)" -f `
        $group.Name, $extensionTotals.Sum, $extensionCodeTotals.Sum, $group.Count))
}
$report.Add("")
$report.Add("Dateiuebersicht (Zeilen insgesamt / nicht-leere Zeilen)")
$report.Add("-------------------------------------------------------")
foreach ($file in $sortedFiles) {
    $report.Add(("{0,8} / {1,-8} {2}" -f $file.TotalLines, $file.CodeLines, $file.Path))
}
$report.Add("")
$report.Add("Gezaehlt werden Quellcode- und Konfigurationsdateien; Kommentare zaehlen mit.")
$report.Add("Build-, Git-, IDE- und Abhaengigkeitsordner werden ausgelassen.")

Set-Content -LiteralPath $ReportPath -Value $report -Encoding UTF8
Write-Output "Bericht erstellt: $ReportPath"
