package com.lugality.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lugality.scraper.config.ScraperSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Local file storage service.
 * Equivalent to Python's local_storage.py.
 *
 * Saves application JSON data, PDF metadata, checkpoints, and progress logs.
 */
@Slf4j
@Service
public class LocalStorageService {

    private final ScraperSettings settings;
    private final ObjectMapper objectMapper;
    private final GoogleDriveService googleDriveService;

    @Autowired
    public LocalStorageService(ScraperSettings settings,
                                ObjectMapper objectMapper,
                                GoogleDriveService googleDriveService) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.googleDriveService = googleDriveService;
    }

    // ─────────────────────────────────────────────────────────────
    // Application data
    // ─────────────────────────────────────────────────────────────

    /**
     * Save application JSON data.
     * Equivalent to Python's save_application_data().
     */
    public Path saveApplicationData(String appNumber, Map<String, Object> data) throws IOException {
        Path dataDir = settings.getLocalDataPath();
        dataDir.toFile().mkdirs();
        Path filepath = dataDir.resolve(appNumber + ".json");

        data.put("_metadata", Map.of(
                "saved_at", LocalDateTime.now().toString(),
                "version", "1.0"
        ));

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(filepath.toFile(), data);
        log.info("Saved data to {}", filepath);

        // ── Google Drive upload ──────────────────────────────────
        googleDriveService.uploadFile(filepath);

        return filepath;
    }

    /**
     * Load previously saved application data.
     */
    public Optional<Map<String, Object>> loadApplicationData(String appNumber) {
        Path filepath = settings.getLocalDataPath().resolve(appNumber + ".json");
        if (!filepath.toFile().exists()) return Optional.empty();
        try {
            Map<String, Object> data = objectMapper.readValue(filepath.toFile(), Map.class);
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("Error loading data for {}: {}", appNumber, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Save document metadata.
     */
    public void saveDocuments(String appNumber, List<Map<String, Object>> documents) throws IOException {
        Path dataDir = settings.getLocalDataPath();
        dataDir.toFile().mkdirs();
        Path filepath = dataDir.resolve(appNumber + "_documents.json");

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(filepath.toFile(), Map.of(
                "application_number", appNumber,
                "documents", documents,
                "saved_at", LocalDateTime.now().toString()
        ));

        log.info("Saved document info to {}", filepath);

        // ── Google Drive upload ──────────────────────────────────
        googleDriveService.uploadFile(filepath);
    }

    // ─────────────────────────────────────────────────────────────
    // Processed applications
    // ─────────────────────────────────────────────────────────────

    /**
     * Get list of already processed application numbers.
     * Equivalent to Python's get_processed_applications().
     */
    public Set<String> getProcessedApplications() {
        Path dataDir = settings.getLocalDataPath();
        Set<String> processed = new HashSet<>();

        if (!dataDir.toFile().exists()) return processed;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith("_documents.json")
                        && !name.equals("checkpoint.json")
                        && !name.equals("progress_log.json")
                        && !name.equals("export.csv")) {
                    processed.add(name.replace(".json", ""));
                }
            }
        } catch (Exception e) {
            log.warn("Error reading processed applications: {}", e.getMessage());
        }

        return processed;
    }

    // ─────────────────────────────────────────────────────────────
    // Checkpoint
    // ─────────────────────────────────────────────────────────────

    /**
     * Save checkpoint for resume capability.
     * Equivalent to Python's save_checkpoint().
     */
    public Path saveCheckpoint(
            List<String> queue,
            List<String> processed,
            List<Map<String, Object>> failed,
            String checkpointFile) throws IOException {

        Path dataDir = settings.getLocalDataPath();
        dataDir.toFile().mkdirs();

        Path filepath = checkpointFile != null
                ? Paths.get(checkpointFile)
                : dataDir.resolve("checkpoint.json");

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("saved_at", LocalDateTime.now().toString());
        checkpoint.put("total_processed", processed.size());
        checkpoint.put("total_failed", failed.size());
        checkpoint.put("remaining", queue.size());
        checkpoint.put("applications_queue", queue);
        checkpoint.put("processed_applications", processed);
        checkpoint.put("failed_applications", failed);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(filepath.toFile(), checkpoint);
        log.info("Checkpoint saved: {} processed, {} remaining", processed.size(), queue.size());

        // ── Google Drive upload ──────────────────────────────────
        googleDriveService.uploadFile(filepath);

        return filepath;
    }

    /**
     * Load checkpoint for resuming.
     */
    public Optional<Map<String, Object>> loadCheckpoint(String checkpointFile) {
        Path filepath = checkpointFile != null
                ? Paths.get(checkpointFile)
                : settings.getLocalDataPath().resolve("checkpoint.json");

        if (!filepath.toFile().exists()) return Optional.empty();

        try {
            Map<String, Object> data = objectMapper.readValue(filepath.toFile(), Map.class);
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("Error loading checkpoint: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Progress log
    // ─────────────────────────────────────────────────────────────

    /**
     * Append a run summary to the progress log.
     * Equivalent to Python's save_progress_entry().
     */
    public void saveProgressEntry(
            int newProcessed, int newFailed, int totalInput,
            double durationSeconds, int workers) throws IOException {

        Path dataDir = settings.getLocalDataPath();
        dataDir.toFile().mkdirs();
        Path logFile = dataDir.resolve("progress_log.json");

        List<Map<String, Object>> log = new ArrayList<>();
        if (logFile.toFile().exists()) {
            try {
                log = objectMapper.readValue(logFile.toFile(), List.class);
            } catch (Exception ignored) {}
        }

        int cumulativeProcessed = getProcessedApplications().size();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", LocalDateTime.now().toString());
        entry.put("new_processed", newProcessed);
        entry.put("new_failed", newFailed);
        entry.put("cumulative_processed", cumulativeProcessed);
        entry.put("total_input", totalInput);
        entry.put("remaining", Math.max(0, totalInput - cumulativeProcessed));
        entry.put("progress_pct", totalInput > 0
                ? Math.round(cumulativeProcessed * 1000.0 / totalInput) / 10.0 : 0);
        entry.put("duration_seconds", Math.round(durationSeconds * 10) / 10.0);
        entry.put("workers", workers);

        log.add(entry);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(logFile.toFile(), log);
    }

    /**
     * Get progress summary.
     * Equivalent to Python's get_progress_summary().
     */
    public Map<String, Object> getProgressSummary() {
        int processed = getProcessedApplications().size();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Map<String, Object>> runs   = new ArrayList<>();
        int totalInput = 0;

        // Load checkpoint for failed count
        Path checkpointPath = settings.getLocalDataPath().resolve("checkpoint.json");
        if (checkpointPath.toFile().exists()) {
            try {
                Map<String, Object> cp = objectMapper.readValue(checkpointPath.toFile(), Map.class);
                failed = (List<Map<String, Object>>) cp.getOrDefault("failed_applications", List.of());
            } catch (Exception ignored) {}
        }

        // Load progress log
        Path logPath = settings.getLocalDataPath().resolve("progress_log.json");
        if (logPath.toFile().exists()) {
            try {
                runs = objectMapper.readValue(logPath.toFile(), List.class);
                if (!runs.isEmpty()) {
                    totalInput = (int) runs.get(runs.size() - 1).getOrDefault("total_input", 0);
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cumulative_processed", processed);
        summary.put("cumulative_failed", failed.size());
        summary.put("total_input", totalInput);
        summary.put("remaining", Math.max(0, totalInput - processed));
        summary.put("progress_pct", totalInput > 0
                ? Math.round(processed * 1000.0 / totalInput) / 10.0 : 0);
        summary.put("runs", runs);
        summary.put("failed_details", failed);
        return summary;
    }

    // ─────────────────────────────────────────────────────────────
    // CSV export
    // ─────────────────────────────────────────────────────────────

    /**
     * Export all data to CSV.
     * Equivalent to Python's export_to_csv().
     */
    public Path exportToCsv() throws IOException {
        Path dataDir = settings.getLocalDataPath();
        Path csvPath = dataDir.resolve("export.csv");

        List<Map<String, Object>> allData = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith("checkpoint") || name.endsWith("_documents.json")
                        || name.equals("progress_log.json")) continue;
                try {
                    Map<String, Object> data = objectMapper.readValue(file.toFile(), Map.class);
                    data.remove("_metadata");
                    allData.add(data);
                } catch (Exception ignored) {}
            }
        }

        if (allData.isEmpty()) { log.warn("No data to export"); return csvPath; }

        Set<String> allFields = new LinkedHashSet<>();
        allData.forEach(d -> allFields.addAll(d.keySet()));

        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath.toFile()))) {
            pw.println(String.join(",", allFields));
            for (Map<String, Object> row : allData) {
                List<String> values = new ArrayList<>();
                for (String field : allFields) {
                    Object val = row.get(field);
                    String strVal = val == null ? "" : val.toString().replace(",", ";").replace("\n", " ");
                    values.add("\"" + strVal + "\"");
                }
                pw.println(String.join(",", values));
            }
        }

        log.info("Exported {} records to {}", allData.size(), csvPath);

        // ── Google Drive upload ──────────────────────────────────
        googleDriveService.uploadFile(csvPath);

        return csvPath;
    }
}
