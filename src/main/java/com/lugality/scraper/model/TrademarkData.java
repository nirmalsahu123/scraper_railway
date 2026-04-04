package com.lugality.scraper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete extracted data for a trademark application.
 * Equivalent to Python's TradeMarkData (Pydantic model).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrademarkData {

    // ── Primary identifier ──
    @JsonProperty("application_number")
    private String applicationNumber;

    // ── Status ──
    @Builder.Default
    private String status = "Unknown";

    @JsonProperty("sub_status")
    private String subStatus;

    @JsonProperty("as_on_date")
    private LocalDate asOnDate;

    // ── Main table data ──
    @JsonProperty("date_of_application")
    private LocalDate dateOfApplication;

    @JsonProperty("trademark_class")
    private String trademarkClass;

    @Builder.Default
    @JsonProperty("filing_mode")
    private String filingMode = "Unknown";

    @JsonProperty("trademark_name")
    private String trademarkName;

    @Builder.Default
    @JsonProperty("tm_type")
    private String tmType = "Unknown";

    @JsonProperty("user_detail")
    private String userDetail;

    // ── Publication ──
    @JsonProperty("publication_details")
    private PublicationDetails publicationDetails;

    // ── Validity ──
    @JsonProperty("valid_upto")
    private LocalDate validUpto;

    @JsonProperty("renewed_upto")
    private LocalDate renewedUpto;

    // ── Owner ──
    @JsonProperty("proprietor_name")
    private String proprietorName;

    // ── Trademark image ──
    @JsonProperty("trademark_image_url")
    private String trademarkImageUrl;

    @JsonProperty("trademark_image_local")
    private String trademarkImageLocal;

    // ── Documents ──
    @Builder.Default
    private List<DocumentInfo> documents = new ArrayList<>();

    // ── Parsed PDF data ──
    @JsonProperty("parsed_documents")
    private ParsedDocuments parsedDocuments;

    // ── Metadata ──
    @Builder.Default
    @JsonProperty("scraped_at")
    private LocalDateTime scrapedAt = LocalDateTime.now();

    // ─────────────────────────────────────────────────
    // Nested models
    // ─────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicationDetails {
        @JsonProperty("journal_number")
        private String journalNumber;

        @JsonProperty("publication_date")
        private LocalDate publicationDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocumentInfo {
        @JsonProperty("document_type")
        private String documentType;

        @JsonProperty("document_name")
        private String documentName;

        private String description;

        @JsonProperty("document_date")
        private String documentDate;

        @JsonProperty("download_url")
        private String downloadUrl;

        @JsonProperty("local_path")
        private String localPath;

        @JsonProperty("file_size_bytes")
        private Long fileSizeBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParsedDocuments {
        @JsonProperty("TM-A")
        private Object tmA;

        @JsonProperty("TM-R")
        private Object tmR;

        @JsonProperty("TM-P")
        private Object tmP;

        @JsonProperty("TM-M")
        private Object tmM;

        @JsonProperty("TM-O")
        private Object tmO;
    }
}
