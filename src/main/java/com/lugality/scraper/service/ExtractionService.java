package com.lugality.scraper.service;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.model.TrademarkData;
import com.lugality.scraper.model.TrademarkData.DocumentInfo;
import com.lugality.scraper.model.TrademarkData.PublicationDetails;
import com.lugality.scraper.workflow.ScraperState;
import com.microsoft.playwright.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts trademark data from the IP India results page.
 * Equivalent to Python's extract.py (extract_data_node, download_uploaded_documents).
 */
@Slf4j
@Service
public class ExtractionService {

    private final ScraperSettings settings;
    private final PdfParserService pdfParser;
    private final ObjectMapper objectMapper;
    private final GoogleDriveService googleDriveService;

    @Autowired
    public ExtractionService(ScraperSettings settings,
                             PdfParserService pdfParser,
                             ObjectMapper objectMapper,
                             GoogleDriveService googleDriveService) {
        this.settings = settings;
        this.pdfParser = pdfParser;
        this.objectMapper = objectMapper;
        this.googleDriveService = googleDriveService;
    }

    /**
     * Main extraction node.
     * Equivalent to Python's extract_data_node().
     */
    public ScraperState extract(ScraperState state, BrowserManager browser) {
        try {
            log.info("Extracting data for application: {}", state.getCurrentApplication());
            state.setCurrentStep(ScraperState.Step.EXTRACTING_DATA);

            Page page = browser.getPage();
            browser.screenshot("results_" + state.getCurrentApplication());

            // 1. Status header
            Map<String, String> statusInfo = extractStatusHeader(page);

            // 2. Table data
            Map<String, String> tableData = extractTableData(page);

            // 3. Trademark image URL
            String imageUrl = getTrademarkImageUrl(page);

            // 4. Download trademark image
            String imageLocalPath = null;
            if (imageUrl != null && !imageUrl.contains("tmr_ereg_img.jpg")) {
                imageLocalPath = downloadImage(imageUrl, state.getCurrentApplication(), browser);
            }

            // 5. Build TrademarkData
            TrademarkData trademark = buildTrademarkData(
                    state.getCurrentApplication(), statusInfo, tableData, imageUrl, imageLocalPath);

            state.setExtractedData(objectMapper.convertValue(trademark, Map.class));
            log.info("Data extracted: {}", trademark.getTrademarkName());

            // 6. Download uploaded documents
            state.setCurrentStep(ScraperState.Step.DOWNLOADING_DOCUMENTS);
            List<DocumentInfo> documents = downloadUploadedDocuments(browser, state.getCurrentApplication());
            List<Map<String, Object>> docMaps = new ArrayList<>();
            documents.forEach(d -> docMaps.add(objectMapper.convertValue(d, Map.class)));
            state.setPdfDocuments(docMaps);

            // Update extracted data with documents
            state.getExtractedData().put("documents", docMaps);
            log.info("Downloaded {} documents", documents.size());

            // 7. Parse trademark PDF documents
            Map<String, Object> parsedDocs = parseTradedmarkDocuments(documents);
            if (!parsedDocs.isEmpty()) {
                state.getExtractedData().put("parsed_documents", parsedDocs);
            }

            state.setCurrentStep(ScraperState.Step.STORING_DATA);

        } catch (Exception e) {
            log.error("Extraction error: {}", e.getMessage());
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage("Extraction error: " + e.getMessage());
            browser.screenshot("extract_error_" + state.getCurrentApplication());
        }

        return state;
    }

    // ─────────────────────────────────────────────────────────────
    // Status header extraction
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> extractStatusHeader(Page page) {
        Map<String, String> result = new HashMap<>();
        result.put("status", "");
        result.put("sub_status", "");
        result.put("as_on_date", null);

        try {
            String text = page.innerText("body");

            Matcher dateMatcher = Pattern.compile(
                    "As\\s+on\\s+Date\\s*:\\s*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (dateMatcher.find()) result.put("as_on_date", dateMatcher.group(1));

            Matcher statusMatcher = Pattern.compile(
                    "(?<!Sub\\s)Status\\s*:\\s*([^\n]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (statusMatcher.find())
                result.put("status", statusMatcher.group(1).strip().replaceAll("\\s+", " "));

            Matcher subStatusMatcher = Pattern.compile(
                    "Sub\\s*Status\\s*:\\s*([^\n]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (subStatusMatcher.find()) {
                String sub = subStatusMatcher.group(1).strip().replaceAll("\\s+", " ");
                if (!sub.equalsIgnoreCase("not applicable")) result.put("sub_status", sub);
            }

        } catch (Exception e) {
            log.warn("Error extracting status header: {}", e.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Table data extraction
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> extractTableData(Page page) {
        Map<String, String> result = new LinkedHashMap<>();

        try {
            List<ElementHandle> tables = page.querySelectorAll("table");
            log.info("Found {} tables on page", tables.size());

            ElementHandle target = null;
            for (ElementHandle table : tables) {
                if (table.querySelectorAll("th").size() >= 5) { target = table; break; }
            }
            if (target == null && !tables.isEmpty()) target = tables.get(0);
            if (target == null) { log.error("No table on page"); return result; }

            // Headers
            List<ElementHandle> headers = target.querySelectorAll("th");
            List<String> headerTexts = new ArrayList<>();
            for (ElementHandle h : headers) {
                String t = h.innerText().strip().replace("\n", " ");
                headerTexts.add(t);
            }
            log.info("Table headers: {}", headerTexts);

            // Data rows
            List<ElementHandle> rows = new ArrayList<>();
            for (ElementHandle row : target.querySelectorAll("tr")) {
                if (!row.querySelectorAll("td").isEmpty()) rows.add(row);
            }

            if (!rows.isEmpty()) {
                List<ElementHandle> cells = rows.get(0).querySelectorAll("td");
                for (int i = 0; i < cells.size() && i < headerTexts.size(); i++) {
                    String val = cells.get(i).innerText().strip().replaceAll("\\s+", " ");
                    result.put(normalizeHeader(headerTexts.get(i)), val);
                }
            }

            log.info("Extracted {} fields from table", result.size());

        } catch (Exception e) {
            log.error("Error extracting table data: {}", e.getMessage());
        }

        return result;
    }

    private String normalizeHeader(String header) {
        return switch (header) {
            case "Trade Mark No", "Trade Mark No." -> "trade_mark_no";
            case "Date of Application"             -> "date_of_application";
            case "Class"                           -> "class";
            case "Filing Mode"                     -> "filing_mode";
            case "Trade Mark"                      -> "trade_mark";
            case "TM Type"                         -> "tm_type";
            case "User Detail"                     -> "user_detail";
            case "Publication Details"             -> "publication_details";
            case "Valid Upto", "Valid Upto/ Renewed Upto", "Valid Upto/Renewed Upto" -> "valid_upto";
            case "Proprietor Name", "Proprietor"   -> "proprietor_name";
            default -> header.toLowerCase().replace(" ", "_").replace("/", "_");
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Trademark image
    // ─────────────────────────────────────────────────────────────

    private String getTrademarkImageUrl(Page page) {
        try {
            List<ElementHandle> images = page.querySelectorAll("img");
            for (ElementHandle img : images) {
                String src = img.getAttribute("src");
                if (src == null) continue;
                if (src.contains("logo") || src.contains("icon") || src.contains("header")) continue;
                if (src.contains("trademark") || src.contains("mark") || src.contains("ereg")) {
                    if (src.startsWith("/")) {
                        String origin = (String) page.evaluate("window.location.origin");
                        src = origin + src;
                    }
                    return src;
                }
            }
        } catch (Exception e) {
            log.warn("Error getting trademark image: {}", e.getMessage());
        }
        return null;
    }

    private String downloadImage(String imageUrl, String appNumber, BrowserManager browser) {
        try {
            Path imagesDir = settings.getOutputPath().resolve("images");
            imagesDir.toFile().mkdirs();

            String ext = imageUrl.toLowerCase().contains(".png") ? ".png"
                       : imageUrl.toLowerCase().contains(".gif") ? ".gif" : ".jpg";
            Path filepath = imagesDir.resolve(appNumber + "_logo" + ext);

            Map<String, String> cookies = browser.getCookies();
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Cookie", buildCookieString(cookies));
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            if (conn.getResponseCode() == 200) {
                Files.write(filepath, conn.getInputStream().readAllBytes());
                log.info("Downloaded trademark image: {} ({} bytes)", filepath.getFileName(), Files.size(filepath));
                return filepath.toString();
            }
        } catch (Exception e) {
            log.warn("Error downloading trademark image: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Document download
    // ─────────────────────────────────────────────────────────────

    /**
     * Download all uploaded documents from the modal.
     * Equivalent to Python's download_uploaded_documents().
     */
    public List<DocumentInfo> downloadUploadedDocuments(BrowserManager browser, String appNumber) {
        List<DocumentInfo> documents = new ArrayList<>();
        Page page = browser.getPage();

        try {
            Path docsDir = settings.getLocalPdfPath().resolve("documents").resolve(appNumber);
            docsDir.toFile().mkdirs();

            // Click Uploaded Documents button
            ElementHandle btn = page.querySelector(
                    "button:has-text('Uploaded Documents'), a:has-text('Uploaded Documents')");
            if (btn == null) {
                log.warn("Uploaded Documents button not found for {}", appNumber);
                return documents;
            }

            log.info("Opening Uploaded Documents modal...");
            btn.click();
            Thread.sleep(3000);

            browser.screenshot("uploaded_docs_modal_" + appNumber);

            // Check for "No Record Found"
            if (page.querySelector("text=No Record Found") != null ||
                page.querySelector("text=No record found") != null) {
                log.info("No uploaded documents for {}", appNumber);
                closeModal(page);
                return documents;
            }

            // Find document table
            ElementHandle modalTable = null;
            for (ElementHandle table : page.querySelectorAll("table")) {
                String html = table.innerHTML();
                if (html.contains("View") && (html.contains("href") || html.contains("onclick"))) {
                    if (table.querySelectorAll("tr").size() > 0) {
                        modalTable = table;
                        if (html.contains("Document") || html.contains("S.No")) break;
                    }
                }
            }

            if (modalTable == null) {
                log.warn("Document table not found in modal for {}", appNumber);
                closeModal(page);
                return documents;
            }

            // Collect all view links and doc info
            List<Map<String, Object>> viewLinks = new ArrayList<>();
            List<ElementHandle> rows = modalTable.querySelectorAll("tr");
            for (int i = 0; i < rows.size(); i++) {
                List<ElementHandle> cells = rows.get(i).querySelectorAll("td");
                if (cells.size() < 2) continue;

                String description = cells.get(1).innerText().strip().replace("\n", " ");
                String date = cells.size() > 2 ? cells.get(2).innerText().strip() : "";
                ElementHandle link = rows.get(i).querySelector("a:has-text('View'), a");

                if (description != null && !description.isBlank()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("link", link);
                    entry.put("description", description);
                    entry.put("date", date);
                    entry.put("index", i);
                    viewLinks.add(entry);
                }
            }

            log.info("Found {} documents to download for {}", viewLinks.size(), appNumber);

            // Download each document
            BrowserContext ctx = page.context();
            for (Map<String, Object> docInfo : viewLinks) {
                ElementHandle link = (ElementHandle) docInfo.get("link");
                String description = (String) docInfo.get("description");
                String date = (String) docInfo.get("date");
                int idx = (int) docInfo.get("index");

                String filename = appNumber + "_" + idx + "_" + sanitizeFilename(description) + ".pdf";
                Path filepath = docsDir.resolve(filename);

                String pdfUrl = null;
                try {
                    // Strategy 1: Extract href directly from the link element (most reliable)
                    String href = link.getAttribute("href");
                    if (href != null && !href.isBlank() && !href.equals("#")) {
                        pdfUrl = resolveUrl(href, page);
                        log.debug("Got PDF URL from href: {}", pdfUrl);
                    }

                    // Strategy 2: Extract onclick JS URL
                    if (pdfUrl == null || !pdfUrl.startsWith("http")) {
                        String onclick = link.getAttribute("onclick");
                        if (onclick != null) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("(?:window\\.open|location\\.href)\\s*[=(]['\"]([^'\"]+)['\"]")
                                    .matcher(onclick);
                            if (m.find()) pdfUrl = resolveUrl(m.group(1), page);
                        }
                    }

                    // Strategy 3: Capture popup window URL
                    if (pdfUrl == null || !pdfUrl.startsWith("http")) {
                        Page[] newPageHolder = new Page[1];
                        ctx.onPage(p -> newPageHolder[0] = p);
                        link.click();
                        Thread.sleep(3000);

                        if (newPageHolder[0] != null) {
                            String popupUrl = newPageHolder[0].url();
                            newPageHolder[0].close();
                            if (popupUrl != null && popupUrl.startsWith("http")) {
                                pdfUrl = popupUrl;
                                log.debug("Got PDF URL from popup: {}", pdfUrl);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("URL extraction failed: {}", e.getMessage());
                }

                if (pdfUrl != null) {
                    try {
                        Map<String, String> cookies = browser.getCookies();
                        HttpURLConnection conn = (HttpURLConnection) new URL(pdfUrl).openConnection();
                        conn.setRequestProperty("Cookie", buildCookieString(cookies));
                        conn.setConnectTimeout(60000);
                        conn.setReadTimeout(60000);

                        if (conn.getResponseCode() == 200) {
                            byte[] content = conn.getInputStream().readAllBytes();
                            Files.write(filepath, content);

                            // ── Google Drive upload ──────────────
                            googleDriveService.uploadFile(filepath);

                            documents.add(DocumentInfo.builder()
                                    .documentType("uploaded_document")
                                    .documentName(filename)
                                    .description(description)
                                    .documentDate(date)
                                    .downloadUrl(pdfUrl)
                                    .localPath(filepath.toString())
                                    .fileSizeBytes((long) content.length)
                                    .build());

                            log.info("Downloaded: {} ({} bytes)", filename, content.length);
                        }
                    } catch (Exception e) {
                        log.warn("HTTP download failed for {}: {}", description, e.getMessage());
                        documents.add(DocumentInfo.builder()
                                .documentType("uploaded_document")
                                .documentName(appNumber + "_" + idx)
                                .description(description)
                                .documentDate(date)
                                .build());
                    }
                } else {
                    documents.add(DocumentInfo.builder()
                            .documentType("uploaded_document")
                            .documentName(appNumber + "_" + idx)
                            .description(description)
                            .documentDate(date)
                            .build());
                }

                Thread.sleep(1000);
            }

            closeModal(page);

        } catch (Exception e) {
            log.error("Error downloading documents for {}: {}", appNumber, e.getMessage());
            closeModal(page);
        }

        long downloaded = documents.stream().filter(d -> d.getLocalPath() != null).count();
        log.info("Downloaded {}/{} documents for {}", downloaded, documents.size(), appNumber);
        return documents;
    }

    // ─────────────────────────────────────────────────────────────
    // PDF parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, Object> parseTradedmarkDocuments(List<DocumentInfo> documents) {
        Map<String, Object> result = new HashMap<>();
        if (documents == null || documents.isEmpty()) return result;

        for (DocumentInfo doc : documents) {
            if (doc.getDescription() == null || doc.getLocalPath() == null) continue;

            // Validate file exists and is non-empty before attempting parse
            java.io.File pdfFile = new java.io.File(doc.getLocalPath());
            if (!pdfFile.exists() || pdfFile.length() == 0) {
                log.warn("PDF file missing or empty — skipping: {}", doc.getLocalPath());
                continue;
            }

            String desc = doc.getDescription().toLowerCase();
            String docType = null;

            if (desc.contains("tm-a") || desc.contains("trade marks application")) docType = "TM-A";
            else if (desc.contains("tm-r"))   docType = "TM-R";
            else if (desc.contains("tm-p") || desc.contains("proprietor")) docType = "TM-P";
            else if (desc.contains("tm-m") || desc.contains("modification")) docType = "TM-M";
            else if (desc.contains("tm-o"))   docType = "TM-O";

            if (docType != null) {
                try {
                    Path pdfPath = Path.of(doc.getLocalPath());
                    Map<String, Object> parsed = pdfParser.parse(pdfPath, docType);

                    // ── NULL CHECK: parse() returns null for encrypted/scanned/corrupt PDFs ──
                    if (parsed == null) {
                        log.warn("PDF parse returned null for {} ({}) — likely encrypted, scanned, or corrupt",
                                docType, doc.getDocumentName());
                        continue;
                    }
                    if (parsed.isEmpty()) {
                        log.debug("PDF parse returned empty result for {} — no text extracted", docType);
                        continue;
                    }

                    parsed.put("document_date", doc.getDocumentDate());
                    parsed.put("source_file",   doc.getDocumentName());
                    result.put(docType, parsed);
                    log.info("Parsed {} document: {}", docType, doc.getDocumentName());

                } catch (Exception e) {
                    log.warn("Failed to parse {} PDF ({}): {}", docType, doc.getDocumentName(), e.getMessage());
                    // Continue — one bad PDF must not fail the whole application
                }
            }
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Build trademark object
    // ─────────────────────────────────────────────────────────────

    private TrademarkData buildTrademarkData(
            String appNumber, Map<String, String> statusInfo,
            Map<String, String> tableData, String imageUrl, String imageLocalPath) {

        return TrademarkData.builder()
                .applicationNumber(appNumber)
                .status(statusInfo.getOrDefault("status", "Unknown"))
                .subStatus(statusInfo.get("sub_status"))
                .asOnDate(parseDate(statusInfo.get("as_on_date")))
                .dateOfApplication(parseDate(tableData.get("date_of_application")))
                .trademarkClass(tableData.get("class"))
                .filingMode(tableData.getOrDefault("filing_mode", "Unknown"))
                .trademarkName(tableData.get("trade_mark"))
                .tmType(tableData.getOrDefault("tm_type", "Unknown"))
                .userDetail(tableData.get("user_detail"))
                .publicationDetails(parsePublicationDetails(tableData.get("publication_details")))
                .validUpto(parseDate(tableData.get("valid_upto")))
                .renewedUpto(parseDate(tableData.get("renewed_upto")))
                .proprietorName(tableData.get("proprietor_name"))
                .trademarkImageUrl(imageUrl)
                .trademarkImageLocal(imageLocalPath)
                .scrapedAt(LocalDateTime.now())
                .build();
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy")
        );
        for (DateTimeFormatter fmt : formats) {
            try { return LocalDate.parse(dateStr.trim(), fmt); } catch (Exception ignored) {}
        }
        return null;
    }

    private PublicationDetails parsePublicationDetails(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher journal = Pattern.compile("Journal\\s*No\\.?\\s*:?\\s*(\\d+[-/]?\\d*)", Pattern.CASE_INSENSITIVE).matcher(raw);
        Matcher date    = Pattern.compile("Dated?:?\\s*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})", Pattern.CASE_INSENSITIVE).matcher(raw);
        // Call find() once and capture result — calling find() twice advances the matcher past the match
        boolean journalFound = journal.find();
        boolean dateFound    = date.find();
        if (!journalFound && !dateFound) return null;
        return PublicationDetails.builder()
                .journalNumber(journalFound ? journal.group(1) : null)
                .publicationDate(dateFound  ? parseDate(date.group(1)) : null)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────

    private void closeModal(Page page) {
        List<String> selectors = List.of(
                "button.close", ".modal button:has-text('×')",
                "button:has-text('Close')", "[data-dismiss='modal']"
        );
        for (String sel : selectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible()) { btn.click(); Thread.sleep(500); return; }
            } catch (Exception ignored) {}
        }
        try { page.keyboard().press("Escape"); Thread.sleep(500); } catch (Exception ignored) {}
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "document";
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").substring(0, Math.min(name.length(), 50));
    }

    private String buildCookieString(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
        return sb.toString();
    }

    /**
     * Resolve a potentially relative URL against the current page origin.
     * Fixes "no protocol" errors when the site returns paths like "/ViewDoc?id=123"
     */
    private String resolveUrl(String url, Page page) {
        if (url == null || url.isBlank()) return null;
        url = url.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        try {
            String origin = (String) page.evaluate("window.location.origin");
            if (url.startsWith("/")) return origin + url;
            // protocol-relative: //example.com/path
            if (url.startsWith("//")) return "https:" + url;
            // relative path: path/to/doc
            String base = (String) page.evaluate("window.location.href");
            base = base.substring(0, base.lastIndexOf('/') + 1);
            return base + url;
        } catch (Exception e) {
            log.warn("Could not resolve URL '{}': {}", url, e.getMessage());
            return url;
        }
    }
}
