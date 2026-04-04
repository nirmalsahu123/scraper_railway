package com.lugality.scraper.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * State maintained throughout the scraping workflow.
 * Equivalent to Python's ScraperState (TypedDict).
 * 
 * In Java we use a mutable POJO instead of LangGraph's TypedDict.
 * The WorkflowOrchestrator passes this state between steps.
 */
@Data
@Builder
public class ScraperState {

    public enum Step {
        INITIALIZING,
        NAVIGATING_TO_LOGIN,
        SOLVING_LOGIN_CAPTCHA,
        SENDING_OTP,
        WAITING_FOR_OTP,
        VERIFYING_OTP,
        NAVIGATING_TO_SEARCH,
        SELECTING_SEARCH_TYPE,
        ENTERING_APPLICATION,
        SOLVING_SEARCH_CAPTCHA,
        EXTRACTING_DATA,
        DOWNLOADING_DOCUMENTS,
        STORING_DATA,
        NEXT_APPLICATION,
        COMPLETED,
        ERROR
    }

    // ── Workflow control ──
    @Builder.Default
    private Step currentStep = Step.INITIALIZING;

    // ── Login ──
    private String loginEmail;
    private String loginPassword;

    // ── Session ──
    @Builder.Default
    private boolean loggedIn = false;

    // ── Application queue ──
    @Builder.Default
    private List<String> applicationsQueue = new ArrayList<>();
    private String currentApplication;

    @Builder.Default
    private int processedCount = 0;

    // ── Results ──
    @Builder.Default
    private List<String> processedApplications = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> failedApplications = new ArrayList<>();

    // ── Extraction ──
    private Map<String, Object> extractedData;

    @Builder.Default
    private List<Map<String, Object>> pdfDocuments = new ArrayList<>();

    // ── Error handling ──
    private String errorMessage;

    @Builder.Default
    private int retryCount = 0;

    @Builder.Default
    private int maxRetries = 3;

    // ── Checkpointing ──
    private String checkpointFile;

    @Builder.Default
    private int lastCheckpointCount = 0;

    // ── Factory method ──
    public static ScraperState initial(
            String email,
            String password,
            List<String> applications,
            int maxRetries,
            String checkpointFile) {

        return ScraperState.builder()
                .loginEmail(email)
                .loginPassword(password)
                .applicationsQueue(new ArrayList<>(applications))
                .maxRetries(maxRetries)
                .checkpointFile(checkpointFile)
                .build();
    }
}
