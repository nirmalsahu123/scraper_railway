package com.lugality.scraper.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF text extraction and parsing service.
 * Equivalent to Python's pdf_parser.py.
 * 
 * Uses Apache PDFBox instead of PyMuPDF (fitz).
 * Supports: TM-A, TM-R, TM-P, TM-M, TM-O forms.
 */
@Slf4j
@Service
public class PdfParserService {

    /**
     * Parse a trademark PDF document.
     * Equivalent to Python's parse_trademark_document().
     */
    public Map<String, Object> parse(Path pdfPath, String docType) {
        String text = extractText(pdfPath);
        if (text == null) return null;

        try {
            Map<String, String> fields = switch (docType) {
                case "TM-A" -> parseTmA(text);
                case "TM-R" -> parseTmR(text);
                case "TM-P" -> parseTmP(text);
                case "TM-M" -> parseTmM(text);
                case "TM-O" -> parseTmO(text);
                default -> { log.warn("Unknown doc type: {}", docType); yield null; }
            };

            if (fields == null) return null;
            return Map.of("document_type", docType, "fields", fields);

        } catch (Exception e) {
            log.error("Error parsing {} document: {}", docType, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Text extraction (PDFBox replaces PyMuPDF/fitz)
    // ─────────────────────────────────────────────────────────────

    private String extractText(Path pdfPath) {
        if (!pdfPath.toFile().exists()) {
            log.error("PDF not found: {}", pdfPath);
            return null;
        }
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.debug("Extracted {} chars from {}", text.length(), pdfPath.getFileName());
            return text;
        } catch (Exception e) {
            log.error("Error extracting PDF text: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TM-A parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> parseTmA(String text) {
        Map<String, String> r = new HashMap<>();
        r.put("temp_ref_no",           extract(text, "Temp\\.\\s*Ref\\.\\s*No[:\\s]*([^\n]+)"));
        r.put("nature_of_application", extract(text, "NATURE\\s+OF\\s+APPLICATION[:\\s]*([^\n]+)"));
        r.put("application_filed_as",  extract(text, "APPLICATION\\s+FILED\\s+AS[:\\s]*([^\n]+)"));
        r.put("fee",                   extract(text, "FEE[:\\s]*(\\d+)"));
        r.put("applicant_name",        extract(text, "Name[:\\s]+([A-Z][^\n]+)"));
        r.put("applicant_email",       extract(text, "Email\\s+Address[:\\s]+([^\\s\n]+)"));
        r.put("applicant_mobile",      extract(text, "Mobile\\s+No\\.[:\\s]*([^\n]*)"));
        r.put("applicant_country",     extract(text, "Country[:\\s]+([^\n]+)"));
        r.put("category_of_mark",      extract(text, "Category\\s+of\\s+Mark[:\\s]+([^\n]+)"));
        r.put("trade_mark",            extract(text, "Trade\\s+Mark[:\\s]+([^\n]+)"));
        r.put("class",                 extract(text, "Class[:\\s]*(\\d+)"));
        r.put("class_description",     extract(text, "Description[:\\s]+([^\n]+)"));
        r.put("statement_of_use",      extract(text, "STATEMENT\\s+AS\\s+TO\\s+USE\\s+OF\\s+MARK[:\\s]*([^\n]+)"));
        r.put("signed_date",           extract(text, "Date[:\\s]+(\\d{2}[-/]\\d{2}[-/]\\d{4})"));
        r.put("digitally_signed_by",   extract(text, "Digitally\\s+Signed\\s+By[:\\s]*([^\n]+)"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // TM-R parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> parseTmR(String text) {
        Map<String, String> r = new HashMap<>();
        r.put("receipt_no",        extract(text, "Receipt\\s*No\\.?:?\\s*(\\d+)"));
        r.put("receipt_date",      extract(text, "Date:\\s*(\\d{2}/\\d{2}/\\d{4})"));
        r.put("amount",            extract(text, "Amount:\\s*Rs\\.?\\s*([\\d,./\\-]+)"));
        r.put("temp_ref",          extract(text, "Temp#\\s*:?\\s*(\\d+)"));
        r.put("request_type",      extract(text, "REQUEST\n([^\n]+?)(?=\nFEE)"));
        r.put("fee_paid",          extract(text, "FEE\\s*PAID\n(\\d+)"));
        r.put("applicant_name",    extract(text, "Applicant\\s*Name\n([^\n]+)"));
        r.put("applicant_mobile",  extract(text, "Mobile\\s*No\n([\\d*]+)"));
        r.put("applicant_email",   extract(text, "Email\\s*address\n([^\\s\n]+@[^\\s\n]+)"));
        r.put("agent_name",        extract(text, "Agent\\s*Name\n([^\n]+)"));
        r.put("trade_mark_no",     extract(text, "Trade\\s*Mark\\s*No\n([\\d]+)"));
        r.put("class",             extract(text, "Class\\(?es\\)?\n([\\d,\\s]+)"));
        r.put("digitally_signed_by", extract(text, "Digitally\\s+Signed\\s+By\n([^\n]+)"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // TM-P parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> parseTmP(String text) {
        Map<String, String> r = new HashMap<>();
        r.put("receipt_no",         extract(text, "Receipt\\s*No\\.?:?\\s*(\\d+)"));
        r.put("request_type",       extract(text, "REQUEST\\s+([A-Z\\s]+?)(?:\n|FEE)"));
        r.put("fee_paid",           extract(text, "FEE\\s*PAID\\s*(\\d+)"));
        r.put("applicant_name",     extract(text, "Applicant\\s*Name\\s+([^\n]+)"));
        r.put("applicant_mobile",   extract(text, "Mobile\\s*No\\s+([\\d*]+)"));
        r.put("applicant_email",    extract(text, "Email\\s*address\\s+([^\\s\n]+@[^\\s\n]+)"));
        r.put("agent_name",         extract(text, "Agent\\s*Name\\s+([^\n]+)"));
        r.put("instrument_for",     extract(text, "Instrument\\s+For\\s+([^\n]+)"));
        r.put("trade_mark_no",      extract(text, "Trade\\s*Mark\\s*No\\s+([\\d]+)"));
        r.put("class",              extract(text, "Class\\(?es\\)?\\s+([\\d,\\s]+)"));
        r.put("digitally_signed_by",extract(text, "Digitally\\s*Signed\\s*By\\s*([^\n]+)"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // TM-M parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> parseTmM(String text) {
        Map<String, String> r = new HashMap<>();
        r.put("receipt_no",          extract(text, "Receipt\\s*No\\.?:?\\s*(\\d+)"));
        r.put("temp_ref",            extract(text, "Temp#\\s*:?\\s*(\\d+)"));
        r.put("request_type",        extract(text, "REQUEST\n([^\n]+)"));
        r.put("fee",                 extract(text, "FEE\n(\\d+)"));
        r.put("applicant_name",      extract(text, "Applicant\nName\n([^\n]+)"));
        r.put("applicant_mobile",    extract(text, "Mobile\\s*No\n([\\d*]+)"));
        r.put("applicant_email",     extract(text, "Email\\s*address\n([^\\s\n]+@[^\\s\n]+)"));
        r.put("agent_name",          extract(text, "Agent\\s*Name\n([^\n]+)"));
        r.put("class",               extract(text, "Class\\(?es\\)?\n(\\d+)"));
        r.put("application_number",  extract(text, "APPLICATION\nNUMBER\n(\\d+)"));
        r.put("details_of_corrections", extract(text, "Details\\s+Of\ncorrections\n([^\n]+)"));
        r.put("date",                extract(text, "Date\n(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}\\s*[AP]M)"));
        r.put("digitally_signed_by", extract(text, "Digitally\\s+Signed\\s+By\n([^\n]+)"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // TM-O parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> parseTmO(String text) {
        Map<String, String> r = new HashMap<>();
        r.put("receipt_no",              extract(text, "Receipt\\s*No\\.?:?\\s*(\\d+)"));
        r.put("temp_number",             extract(text, "Temp\\s*Number\\s*:?\\s*(\\d+)"));
        r.put("request_type",            extract(text, "REQUEST\n([^\n]+?)(?=\nFEE)"));
        r.put("fee",                     extract(text, "FEE\n(\\d+)"));
        r.put("applicant_name",          extract(text, "Applicant\\s*Name\n([^\n]+)"));
        r.put("agent_name",              extract(text, "Agent\\s*Name\n([^\n]+)"));
        r.put("rectification_number",    extract(text, "DETAILS\\s+OF\nRECTIFICATION\nNUMBER\n(\\d+)"));
        r.put("class",                   extract(text, "CLASS\n(\\d+)"));
        r.put("grounds_for_rectification", extract(text, "GROUNDS\\s+FOR\nRECTIFICATION\n([^\n]+)"));
        r.put("date",                    extract(text, "Date\n(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}\\s*[AP]M)"));
        r.put("digitally_signed_by",     extract(text, "Digitally\\s+Signed\\s+By\n([^\n]+)"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────

    private String extract(String text, String regexPattern) {
        try {
            Matcher m = Pattern.compile(regexPattern,
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(text);
            if (m.find()) return m.group(1).strip();
        } catch (Exception ignored) {}
        return "";
    }
}
