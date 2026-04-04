package com.lugality.scraper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application configuration - equivalent to Python's config.py (Pydantic Settings).
 * All values are loaded from application.yml / environment variables.
 */
@Data
@Component
@ConfigurationProperties(prefix = "scraper")
public class ScraperSettings {

    // ── Email (Worker 0 / primary) ──
    private String emailAddress;
    private String emailAppPassword;
    private String imapServer = "imap.hostinger.com";

    // ── Additional worker email accounts (optional) ──
    private String emailAddress1;
    private String emailAppPassword1;
    private String emailAddress2;
    private String emailAppPassword2;
    private String emailAddress3;
    private String emailAppPassword3;
    private String emailAddress4;
    private String emailAppPassword4;
    private String emailAddress5;
    private String emailAppPassword5;
    private String emailAddress6;
    private String emailAppPassword6;
    private String emailAddress7;
    private String emailAppPassword7;
    private String emailAddress8;
    private String emailAppPassword8;
    private String emailAddress9;
    private String emailAppPassword9;
    private String emailAddress10;
    private String emailAppPassword10;




    // ── Scraper settings ──
    private String targetUrl = "https://tmrsearch.ipindia.gov.in/estatus";
    private int rateLimitPerMinute = 8;
    private int delayBetweenSearches = 15;
    private int maxRetries = 3;
    private int sessionRefreshMinutes = 50;
    private int otpTimeoutSeconds = 350;
    // ── Parallel settings ──
    private int numWorkers = 10;
    private int maxConcurrentDownloads = 5;
    private int checkpointInterval = 25;
    private int browserRecycleInterval = 60;
    private int appTimeoutSeconds = 180;

    // ── Storage ──
    private String storageMode = "local";
    private String localDataDir = "./output/data";
    private String localPdfDir = "./output/pdfs";

    // ── Supabase (optional) ──
    private String supabaseUrl;
    private String supabaseKey;

    // ── AWS S3 (optional) ──
    private String awsAccessKeyId;
    private String awsSecretAccessKey;
    private String s3BucketName;
    private String s3Region = "ap-south-1";

    // ── Google Drive (optional) ──
    private boolean googleDriveEnabled = false;
    private String googleDriveClientId;
    private String googleDriveClientSecret;
    private String googleDriveRefreshToken;
    private String googleDriveFolderId;

    // ── Browser ──
    private boolean headless = true;
    private int browserTimeout = 30000;

    // ── Computed paths ──
    public Path getLocalDataPath() {
        return Paths.get(localDataDir);
    }

    public Path getLocalPdfPath() {
        return Paths.get(localPdfDir);
    }

    public Path getOutputPath() {
        return Paths.get(localDataDir).getParent();
    }

    /** Get just the email string for a worker. */
    public String getWorkerEmail(int workerId) {
        return getWorkerCredentials(workerId).email();
    }

    /** Get just the password string for a worker. */
    public String getWorkerPassword(int workerId) {
        return getWorkerCredentials(workerId).password();
    }

    /**
     * Get email credentials for a specific worker.
     * Equivalent to Python's get_worker_email(worker_id).
     */
    public WorkerCredentials getWorkerCredentials(int workerId) {
        return switch (workerId) {
            case 0 -> new WorkerCredentials(emailAddress, emailAppPassword);
            case 1 -> hasCredentials(emailAddress1, emailAppPassword1)
                    ? new WorkerCredentials(emailAddress1, emailAppPassword1)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 2 -> hasCredentials(emailAddress2, emailAppPassword2)
                    ? new WorkerCredentials(emailAddress2, emailAppPassword2)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 3 -> hasCredentials(emailAddress3, emailAppPassword3)
                    ? new WorkerCredentials(emailAddress3, emailAppPassword3)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 4 -> hasCredentials(emailAddress4, emailAppPassword4)
                    ? new WorkerCredentials(emailAddress4, emailAppPassword4)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 5 -> hasCredentials(emailAddress5, emailAppPassword5)
                    ? new WorkerCredentials(emailAddress5, emailAppPassword5)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 6 -> hasCredentials(emailAddress6, emailAppPassword6)
                    ? new WorkerCredentials(emailAddress6, emailAppPassword6)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 7 -> hasCredentials(emailAddress7, emailAppPassword7)
                    ? new WorkerCredentials(emailAddress7, emailAppPassword7)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 8 -> hasCredentials(emailAddress8, emailAppPassword8)
                    ? new WorkerCredentials(emailAddress8, emailAppPassword8)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 9 -> hasCredentials(emailAddress9, emailAppPassword9)
                    ? new WorkerCredentials(emailAddress9, emailAppPassword9)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            case 10 -> hasCredentials(emailAddress10, emailAppPassword10)
                    ? new WorkerCredentials(emailAddress10, emailAppPassword10)
                    : new WorkerCredentials(emailAddress, emailAppPassword);
            default -> new WorkerCredentials(emailAddress, emailAppPassword);
        };
    }

    private boolean hasCredentials(String email, String password) {
        return StringUtils.hasText(email) && StringUtils.hasText(password);
    }

    public record WorkerCredentials(String email, String password) {}
}