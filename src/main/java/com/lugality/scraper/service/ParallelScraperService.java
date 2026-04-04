package com.lugality.scraper.service;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.workflow.ScraperState;
import com.lugality.scraper.workflow.WorkflowOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Parallel scraper service using Java 21 Virtual Threads.
 *
 * Worker startup flow (as requested):
 *   Worker0 → Browser0 → Login → starts scraping
 *   Worker1 → Browser1 → Login → starts scraping   (after Worker0 login completes)
 *   Worker2 → Browser2 → Login → starts scraping   (after Worker1 login completes)
 *   Worker3 → Browser3 → Login → starts scraping   (after Worker2 login completes)
 *
 * Workers start ONE AT A TIME (sequential login phase) to avoid hammering the
 * IP India portal with simultaneous OTP requests. Once all workers are logged in,
 * they scrape their partitions fully in parallel.
 *
 * Within each worker: 30-second delay between every application.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ParallelScraperService {

    private final WorkflowOrchestrator orchestrator;
    private final ScraperSettings settings;
    private final LocalStorageService localStorageService;

    // Semaphore that allows only ONE worker to be in the login phase at a time.
    // After login, the worker releases the permit so the next worker can log in.
    private final Semaphore loginGate = new Semaphore(1);

    public record BatchResult(
            List<String> processedApplications,
            List<Map<String, Object>> failedApplications,
            List<WorkerResult> workerResults,
            long totalTimeSeconds
    ) {}

    public record WorkerResult(
            int workerId,
            List<String> processed,
            List<Map<String, Object>> failed,
            long totalTimeSeconds,
            String error
    ) {}

    /**
     * Run scraping in parallel across N workers.
     * Workers log in one-at-a-time, then scrape simultaneously.
     */
    public BatchResult runParallel(
            String email,
            List<String> applications,
            int numWorkers,
            boolean resume
    ) throws InterruptedException {

        Instant start = Instant.now();
        int actualWorkers = Math.min(numWorkers, applications.size());
        log.info("Starting parallel scrape: {} apps, {} workers (virtual threads)",
                applications.size(), actualWorkers);

        List<List<String>> partitions = partition(applications, actualWorkers);

        // CachedThreadPool — one platform thread per worker, Java 17 compatible.
        // Switch to Executors.newVirtualThreadPerTaskExecutor() on Java 21.
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("scraper-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        List<Future<WorkerResult>> futures = new ArrayList<>();

        for (int i = 0; i < actualWorkers; i++) {
            final int workerId = i;
            final List<String> partition = partitions.get(i);
            final String workerEmail    = settings.getWorkerEmail(workerId);
            final String workerPassword = settings.getWorkerPassword(workerId);

            futures.add(executor.submit(() ->
                    runWorker(workerId, workerEmail, workerPassword, partition, resume)
            ));
        }

        executor.shutdown();
        executor.awaitTermination(24, TimeUnit.HOURS);

        // Collect results
        List<WorkerResult> workerResults = new ArrayList<>();
        List<String> allProcessed = new ArrayList<>();
        List<Map<String, Object>> allFailed = new ArrayList<>();

        for (Future<WorkerResult> future : futures) {
            try {
                WorkerResult result = future.get();
                workerResults.add(result);
                allProcessed.addAll(result.processed());
                allFailed.addAll(result.failed());
            } catch (ExecutionException e) {
                log.error("Worker execution exception: {}", e.getMessage());
                workerResults.add(new WorkerResult(-1, List.of(), List.of(), 0, e.getMessage()));
            }
        }

        long totalSeconds = Instant.now().getEpochSecond() - start.getEpochSecond();
        log.info("Parallel scrape complete. Processed: {}, Failed: {}, Time: {}s",
                allProcessed.size(), allFailed.size(), totalSeconds);

        try {
            localStorageService.saveProgressEntry(
                    allProcessed.size(), allFailed.size(),
                    applications.size(), (double) totalSeconds, actualWorkers);
        } catch (Exception e) {
            log.warn("Could not save progress entry: {}", e.getMessage());
        }

        return new BatchResult(allProcessed, allFailed, workerResults, totalSeconds);
    }

    /**
     * Each worker:
     *  1. Acquires loginGate  → only ONE worker is logging in at a time
     *  2. Starts its own browser and logs in
     *  3. Releases loginGate  → IMMEDIATELY after login, next worker starts logging in
     *  4. Scrapes its partition independently in parallel (with 30s delay between apps)
     */
    private WorkerResult runWorker(
            int workerId,
            String email,
            String password,
            List<String> applications,
            boolean resume
    ) {
        Instant start = Instant.now();
        log.info("[Worker {}] Waiting for login slot... (email: {})", workerId, email);

        try {
            // ── STEP 1: Acquire gate — only one worker logs in at a time ──
            loginGate.acquire();
            log.info("[Worker {}] Login slot acquired — starting browser & login", workerId);

            WorkflowOrchestrator.WorkerContext ctx;
            try {
                // loginOnly() — starts browser, does login, returns context
                ctx = orchestrator.loginOnly(email, password, applications, resume, workerId);
            } finally {
                // ── STEP 2: Release gate IMMEDIATELY after login ──
                // Next worker can now start logging in while this worker scrapes
                loginGate.release();
                log.info("[Worker {}] Login slot released — next worker can now login", workerId);
            }

            if (ctx == null) {
                long elapsed = Instant.now().getEpochSecond() - start.getEpochSecond();
                log.error("[Worker {}] Login failed — worker aborting", workerId);
                return new WorkerResult(workerId, List.of(), List.of(), elapsed, "Login failed");
            }

            // ── STEP 3: Scrape independently in parallel ──
            ScraperState state = orchestrator.scrape(ctx, workerId);

            long elapsed = Instant.now().getEpochSecond() - start.getEpochSecond();
            log.info("[Worker {}] Done. Processed: {}, Failed: {}, Time: {}s",
                    workerId,
                    state.getProcessedApplications().size(),
                    state.getFailedApplications().size(),
                    elapsed);

            return new WorkerResult(
                    workerId,
                    state.getProcessedApplications(),
                    state.getFailedApplications(),
                    elapsed,
                    null
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsed = Instant.now().getEpochSecond() - start.getEpochSecond();
            log.error("[Worker {}] Interrupted: {}", workerId, e.getMessage());
            return new WorkerResult(workerId, List.of(), List.of(), elapsed, "Interrupted");

        } catch (Exception e) {
            long elapsed = Instant.now().getEpochSecond() - start.getEpochSecond();
            log.error("[Worker {}] Fatal error: {}", workerId, e.getMessage(), e);
            return new WorkerResult(workerId, List.of(), List.of(), elapsed, e.getMessage());
        }
    }

    private <T> List<List<T>> partition(List<T> list, int n) {
        List<List<T>> result = new ArrayList<>();
        int size = list.size();
        int base = size / n;
        int extra = size % n;
        int idx = 0;
        for (int i = 0; i < n; i++) {
            int chunkSize = base + (i < extra ? 1 : 0);
            result.add(new ArrayList<>(list.subList(idx, idx + chunkSize)));
            idx += chunkSize;
        }
        return result;
    }

    @Async("scraperExecutor")
    public CompletableFuture<BatchResult> runParallelAsync(
            String email,
            List<String> applications,
            int numWorkers,
            boolean resume
    ) {
        try {
            BatchResult result = runParallel(email, applications, numWorkers, resume);
            return CompletableFuture.completedFuture(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }
}