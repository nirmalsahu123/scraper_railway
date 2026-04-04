# ── Config ────────────────────────────────────────────────────────────────────
$LOG     = "C:\Users\91722\Downloads\lugality-scraper-fixed\final_project\scraper.log"
$TOTAL   = 658
$WORKERS = 4
$REFRESH = 5
# ─────────────────────────────────────────────────────────────────────────────

$startedAt = Get-Date

while ($true) {
    Clear-Host
    $now     = Get-Date
    $elapsed = ($now - $startedAt).TotalSeconds

    $elH   = [math]::Floor($elapsed / 3600)
    $elM   = [math]::Floor(($elapsed % 3600) / 60)
    $elS   = [math]::Floor($elapsed % 60)
    $elStr = "$($elH.ToString('00')):$($elM.ToString('00')):$($elS.ToString('00'))"

    if (-not (Test-Path $LOG)) {
        Write-Host ""
        Write-Host "  LOG FILE NOT FOUND:" -ForegroundColor Red
        Write-Host "  $LOG" -ForegroundColor DarkGray
        Write-Host "  Waiting..." -ForegroundColor DarkGray
        Start-Sleep $REFRESH
        continue
    }

    $lines = Get-Content $LOG -Encoding Unicode -ErrorAction SilentlyContinue
    if (-not $lines) {
        $lines = Get-Content $LOG -Encoding UTF8 -ErrorAction SilentlyContinue
    }

    # ── Parse counts from log ─────────────────────────────────────────────────
    $done   = ($lines | Select-String "\[Worker \d+\].* done").Count
    $failed = ($lines | Select-String "\[Worker \d+\].* failed").Count
    $total_done = $done + $failed
    $remaining  = $TOTAL - $total_done
    $pct        = if ($TOTAL -gt 0) { [math]::Round(($total_done / $TOTAL) * 100, 1) } else { 0 }

    # ── ETA ───────────────────────────────────────────────────────────────────
    $etaStr = "--:--:--"
    if ($total_done -gt 3 -and $elapsed -gt 0) {
        $spa    = $elapsed / $total_done
        $etaSec = ($remaining / $WORKERS) * $spa
        $etaH   = [math]::Floor($etaSec / 3600)
        $etaM   = [math]::Floor(($etaSec % 3600) / 60)
        $etaS   = [math]::Floor($etaSec % 60)
        $etaStr = "$($etaH.ToString('00')):$($etaM.ToString('00')):$($etaS.ToString('00'))"
        $finishAt = $now.AddSeconds($etaSec).ToString("hh:mm tt")
    } else {
        $finishAt = "--"
    }

    # ── Progress bar ──────────────────────────────────────────────────────────
    $barW   = 40
    $filled = [math]::Min($barW, [math]::Round(($pct / 100) * $barW))
    $empty  = $barW - $filled
    $bar    = ("#" * $filled) + ("-" * $empty)

    # ── Per-worker status ─────────────────────────────────────────────────────
    $workerLines = @()
    for ($w = 0; $w -lt $WORKERS; $w++) {
        $wDone = ($lines | Select-String "\[Worker $w\].* done").Count
        $wFail = ($lines | Select-String "\[Worker $w\].* failed").Count

        $wLast = $lines | Select-String "\[Worker $w\] Processing" | Select-Object -Last 1
        $wApp  = if ($wLast) {
            $parts = $wLast.Line -split "Processing "
            if ($parts.Count -gt 1) { ($parts[1] -split " ")[0] } else { "..." }
        } else { "waiting" }

        $wProgress = ""
        if ($wLast) {
            if ($wLast.Line -match "\((\d+)/(\d+)\)") {
                $wProgress = "($($matches[1])/$($matches[2]))"
            }
        }

        $workerLines += [PSCustomObject]@{
            Id       = $w
            Done     = $wDone
            Failed   = $wFail
            App      = $wApp
            Progress = $wProgress
        }
    }

    # ── Last 4 completed ─────────────────────────────────────────────────────
    $recent = $lines | Select-String "\[Worker \d+\].*(done|failed)" | Select-Object -Last 4

    # ── Draw ──────────────────────────────────────────────────────────────────
    Write-Host ""
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host ("  |  LUGALITY SCRAPER  [final_project]  {0}  |" -f $now.ToString("HH:mm:ss")) -ForegroundColor Cyan
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host ("  |  [{0}]  {1,5}%  |" -f $bar, $pct) -ForegroundColor Yellow
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host ("  |  Total      : {0,-35}|" -f $TOTAL) -ForegroundColor White
    Write-Host ("  |  Done       : {0,-35}|" -f $done) -ForegroundColor Green
    Write-Host ("  |  Failed     : {0,-35}|" -f $failed) -ForegroundColor $(if ($failed -gt 0) { "Red" } else { "White" })
    Write-Host ("  |  Remaining  : {0,-35}|" -f $remaining) -ForegroundColor White
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host ("  |  Elapsed    : {0,-35}|" -f $elStr) -ForegroundColor Green
    Write-Host ("  |  ETA        : {0,-35}|" -f $etaStr) -ForegroundColor Yellow
    Write-Host ("  |  Finish At  : {0,-35}|" -f $finishAt) -ForegroundColor Yellow
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host "  |  WORKER   DONE   FAILED   CURRENT APP            |" -ForegroundColor Cyan
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan

    foreach ($w in $workerLines) {
        $appShort = if ($w.App.Length -gt 14) { $w.App.Substring(0,14) } else { $w.App }
        Write-Host ("  |  W{0}       {1,-5}  {2,-7}  {3,-6} {4,-15}    |" -f `
            $w.Id, $w.Done, $w.Failed, $appShort, $w.Progress) -ForegroundColor White
    }

    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host "  |  RECENT COMPLETED                                |" -ForegroundColor Cyan
    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan

    if ($recent.Count -eq 0) {
        Write-Host "  |  No completions yet...                          |" -ForegroundColor DarkGray
    } else {
        foreach ($entry in $recent) {
            $line  = $entry.Line
            $app   = if ($line -match "(\d{5,9}) (done|failed)") { $matches[1] } else { "?????" }
            $isDone = $line -match " done"
            $mark  = if ($isDone) { "OK" } else { "FAIL" }
            $ts    = if ($line -match "T(\d{2}:\d{2}:\d{2})") { $matches[1] } else { "--:--:--" }
            $col   = if ($isDone) { "Green" } else { "Red" }
            Write-Host ("  |  [{0}]  {1,-5}  App: {2,-28}|" -f $ts, $mark, $app) -ForegroundColor $col
        }
    }

    Write-Host "  +--------------------------------------------------+" -ForegroundColor Cyan
    Write-Host "  Ctrl+C to stop  |  refresh every ${REFRESH}s  |  reading from log" -ForegroundColor DarkGray
    Write-Host ""

    Start-Sleep $REFRESH
}