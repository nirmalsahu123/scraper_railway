package com.lugality.scraper.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.lugality.scraper.config.ScraperSettings;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

@Slf4j
@Service
public class GoogleDriveService {

    private static final String APPLICATION_NAME = "LugalityScraper";

    private final ScraperSettings settings;
    private Drive driveClient;

    @Autowired
    public GoogleDriveService(ScraperSettings settings) {
        this.settings = settings;
    }

    @PostConstruct
    public void init() {
        if (!settings.isGoogleDriveEnabled()) {
            log.info("Google Drive upload is DISABLED");
            return;
        }
        try {
            Credential credential = new GoogleCredential.Builder()
                    .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                    .setJsonFactory(GsonFactory.getDefaultInstance())
                    .setClientSecrets(
                            settings.getGoogleDriveClientId(),
                            settings.getGoogleDriveClientSecret())
                    .build()
                    .setRefreshToken(settings.getGoogleDriveRefreshToken());

            driveClient = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            log.info("Google Drive client initialised (OAuth2 Refresh Token).");
        } catch (IOException | GeneralSecurityException e) {
            log.error("Failed to initialise Google Drive client: {}", e.getMessage(), e);
        }
    }

    public boolean isAvailable() {
        return driveClient != null;
    }

    public String uploadFile(Path localFile) {
        return uploadFile(localFile, settings.getGoogleDriveFolderId());
    }

    public String uploadFile(Path localFile, String targetFolderId) {
        if (!isAvailable()) {
            log.debug("Drive client not available – skipping upload of {}", localFile);
            return null;
        }
        java.io.File javaFile = localFile.toFile();
        if (!javaFile.exists()) {
            log.warn("File does not exist, skipping Drive upload: {}", localFile);
            return null;
        }
        try {
            File fileMetadata = new File();
            fileMetadata.setName(javaFile.getName());
            if (targetFolderId != null && !targetFolderId.isBlank()) {
                fileMetadata.setParents(List.of(targetFolderId));
            }
            FileContent mediaContent = new FileContent(detectMimeType(localFile.toString()), javaFile);
            File uploaded = driveClient.files()
                    .create(fileMetadata, mediaContent)
                    .setFields("id, name, webViewLink")
                    .execute();
            log.info("Uploaded '{}' → Drive id={} link={}", javaFile.getName(), uploaded.getId(), uploaded.getWebViewLink());
            return uploaded.getId();
        } catch (IOException e) {
            log.error("Failed to upload '{}' to Google Drive: {}", localFile, e.getMessage(), e);
            return null;
        }
    }

    private String detectMimeType(String fileName) {
        if (fileName.endsWith(".json")) return "application/json";
        if (fileName.endsWith(".csv"))  return "text/csv";
        if (fileName.endsWith(".pdf"))  return "application/pdf";
        if (fileName.endsWith(".txt"))  return "text/plain";
        return "application/octet-stream";
    }
}
