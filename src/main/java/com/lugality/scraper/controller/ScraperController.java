package com.lugality.scraper.controller;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.service.LocalStorageService;
import com.lugality.scraper.service.ParallelScraperService;
import com.lugality.scraper.service.ParallelScraperService.BatchResult;
import com.lugality.scraper.workflow.ScraperState;
import com.lugality.scraper.workflow.WorkflowOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for the IP India Trademark Scraper.
 *
 * Equivalent to Python's server.py (aiohttp routes) + CLI entry points (main.py).
 *
 * Endpoints:
 *   POST /api/scrape/single       - Scrape one application number
 *   POST /api/scrape/batch        - Scrape a list of application numbers (parallel)
 *   POST /api/scrape/upload       - Upload JSON/CSV/TXT file and scrape
 *   GET  /api/scrape/status/{id}  - Poll batch job status
 *   GET  /api/status              - Overall progress summary
 *   GET  /api/export/csv          - Export all data as CSV
 *   GET  /api/data/{appNumber}    - Get scraped data for one application
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScraperController {

    private final ParallelScraperService parallelScraperService;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final LocalStorageService localStorageService;
    private final ScraperSettings settings;

    // In-memory job tracking (use Redis/DB in production)
    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────────────────────────────────
    // Request / Response DTOs
    // ──────────────────────────────────────────────────────────────────────────

    public record SingleScrapeRequest(
            String email,
            String applicationNumber
    ) {}

    public record BatchScrapeRequest(
            String email,
            List<String> applications,
            int workers,
            boolean resume
    ) {}

    public record JobStatus(
            String jobId,
            String status,           // PENDING | RUNNING | COMPLETED | FAILED
            int total,
            int processed,
            int failed,
            String errorMessage,
            BatchResult result
    ) {}

    // ──────────────────────────────────────────────────────────────────────────
    // Endpoints
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/scrape/single
     * Scrape a single trademark application synchronously.
     */
    @PostMapping("/scrape/single")
    public ResponseEntity<Map<String, Object>> scrapeSingle(
            @RequestBody SingleScrapeRequest request
    ) {
        log.info("Single scrape request: {}", request.applicationNumber());

        try {
            String password = resolvePasswordForEmail(request.email());

            // Use loginOnly → scrape flow (run() has been replaced)
            WorkflowOrchestrator.WorkerContext ctx = workflowOrchestrator.loginOnly(
                    request.email(), password,
                    List.of(request.applicationNumber()), false, 0);

            if (ctx == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "applicationNumber", request.applicationNumber(),
                        "error", "Login failed"
                ));
            }

            ScraperState state = workflowOrchestrator.scrape(ctx, 0);

            if (!state.getProcessedApplications().isEmpty()) {
                Map<String, Object> data = localStorageService.loadApplicationData(request.applicationNumber())
                        .orElse(Map.of());
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "applicationNumber", request.applicationNumber(),
                        "data", data != null ? data : Map.of()
                ));
            } else {
                String error = state.getFailedApplications().isEmpty()
                        ? "Unknown error"
                        : state.getFailedApplications().get(0).getOrDefault("error", "Unknown").toString();
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "applicationNumber", request.applicationNumber(),
                        "error", error
                ));
            }
        } catch (Exception e) {
            log.error("Single scrape failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/scrape/batch
     * Start a parallel batch scraping job (async). Returns a job ID.
     */
    @PostMapping("/scrape/batch")
    public ResponseEntity<Map<String, Object>> scrapeBatch(
            @RequestBody BatchScrapeRequest request
    ) {
        String jobId = UUID.randomUUID().toString();
        int workers = request.workers() > 0 ? request.workers() : settings.getNumWorkers();

        log.info("Batch scrape job {} started: {} apps, {} workers",
                jobId, request.applications().size(), workers);

        JobStatus pending = new JobStatus(jobId, "RUNNING",
                request.applications().size(), 0, 0, null, null);
        jobs.put(jobId, pending);

        // Run async
        CompletableFuture<BatchResult> future = parallelScraperService.runParallelAsync(
                request.email(),
                request.applications(),
                workers,
                request.resume()
        );

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Job {} failed: {}", jobId, ex.getMessage());
                jobs.put(jobId, new JobStatus(jobId, "FAILED",
                        request.applications().size(), 0, 0, ex.getMessage(), null));
            } else {
                log.info("Job {} completed. Processed: {}, Failed: {}",
                        jobId, result.processedApplications().size(), result.failedApplications().size());
                jobs.put(jobId, new JobStatus(
                        jobId, "COMPLETED",
                        request.applications().size(),
                        result.processedApplications().size(),
                        result.failedApplications().size(),
                        null, result
                ));
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "status", "RUNNING",
                "total", request.applications().size(),
                "workers", workers,
                "message", "Batch job started. Poll /api/scrape/status/" + jobId
        ));
    }

    /**
     * POST /api/scrape/upload
     * Upload a JSON/CSV/TXT file with application numbers, then start batch scrape.
     */
    @PostMapping("/scrape/upload")
    public ResponseEntity<Map<String, Object>> scrapeUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("email") String email,
            @RequestParam(value = "workers", defaultValue = "3") int workers,
            @RequestParam(value = "resume", defaultValue = "false") boolean resume
    ) {
        try {
            // Save temp file
            Path tempFile = Files.createTempFile("upload_", "_" + file.getOriginalFilename());
            file.transferTo(tempFile);

            List<String> applications = parseApplicationFile(tempFile);
            Files.deleteIfExists(tempFile);

            log.info("File upload: {} applications loaded from {}", applications.size(), file.getOriginalFilename());

            // Delegate to batch scrape
            return scrapeBatch(new BatchScrapeRequest(email, applications, workers, resume));

        } catch (Exception e) {
            log.error("File upload failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Failed to parse file: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/scrape/status/{jobId}
     * Poll the status of an async batch job.
     */
    @GetMapping("/scrape/status/{jobId}")
    public ResponseEntity<Map<String, Object>> getJobStatus(@PathVariable String jobId) {
        JobStatus job = jobs.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", job.status());
        response.put("total", job.total());
        response.put("processed", job.processed());
        response.put("failed", job.failed());

        if (job.errorMessage() != null) {
            response.put("error", job.errorMessage());
        }
        if (job.result() != null) {
            response.put("totalTimeSeconds", job.result().totalTimeSeconds());
            response.put("workerResults", job.result().workerResults());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/status
     * Cumulative progress summary (equivalent to --status CLI flag).
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> summary = localStorageService.getProgressSummary();
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/export/csv
     * Export all scraped data as CSV file download.
     */
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv() {
        try {
            java.nio.file.Path csvPath = localStorageService.exportToCsv();
            byte[] csv = java.nio.file.Files.readAllBytes(csvPath);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition", "attachment; filename=trademarks.csv")
                    .body(csv);
        } catch (Exception e) {
            log.error("CSV export failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/data/{applicationNumber}
     * Retrieve scraped JSON data for a specific application.
     */
    @GetMapping("/data/{applicationNumber}")
    public ResponseEntity<Map<String, Object>> getApplicationData(
            @PathVariable String applicationNumber
    ) {
        return localStorageService.loadApplicationData(applicationNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/data
     * List all scraped application numbers.
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> listApplications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        List<String> all = new java.util.ArrayList<>(localStorageService.getProcessedApplications());
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);

        return ResponseEntity.ok(Map.of(
                "total", total,
                "page", page,
                "size", size,
                "applications", all.subList(from, to)
        ));
    }

    /**
     * Resolve the IMAP password for the given email address by checking
     * all configured worker credentials. Falls back to primary account password.
     *
     * FIX: the original code always used settings.getEmailAppPassword() regardless
     * of which email was supplied — this broke multi-account setups.
     */
    private String resolvePasswordForEmail(String email) {
        if (email == null) return settings.getEmailAppPassword();
        for (int i = 0; i <= 4; i++) {
            ScraperSettings.WorkerCredentials creds = settings.getWorkerCredentials(i);
            if (email.equalsIgnoreCase(creds.email())) {
                return creds.password();
            }
        }
        return settings.getEmailAppPassword();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: parse application numbers from uploaded file
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> parseApplicationFile(Path path) throws IOException {
        String filename = path.getFileName().toString().toLowerCase();

        if (filename.endsWith(".txt")) {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        if (filename.endsWith(".csv")) {
            List<String> result = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.split(",");
                    if (cols.length == 0) continue;
                    String val = cols[0].trim();
                    // Skip header
                    if (first && !val.matches("\\d+")) { first = false; continue; }
                    first = false;
                    if (!val.isEmpty()) result.add(val);
                }
            }
            return result;
        }

        // JSON
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Object json = mapper.readValue(path.toFile(), Object.class);

        if (json instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        } else if (json instanceof Map<?, ?> map && map.containsKey("applications")) {
            List<?> apps = (List<?>) map.get("applications");
            return apps.stream().map(Object::toString).toList();
        }

        throw new IllegalArgumentException("Unsupported JSON format. Expected array or {\"applications\":[...]}");
    }
}