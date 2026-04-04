package com.lugality.scraper.service;

import com.lugality.scraper.config.ScraperSettings;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OTP codes from email inbox via IMAP.
 *
 * Key improvements:
 *  - Default timeout 360s (up from 300s) — Hostinger SMTP can take 3-4 min
 *  - Caller can trigger OTP resend at 90s via resendCallback
 *  - Persistent IMAP store reused within one OTP wait cycle (avoids reconnect overhead)
 *  - Cutoff window extended to 5 minutes back (was 2 min)
 *  - 3 IMAP connect retries with exponential backoff
 */
@Slf4j
@Service
public class OtpReaderService {

    private static final List<String> SENDER_PATTERNS = List.of(
            "ipindia.gov.in", "ipindia.nic.in", "cgpdtm.nic.in", "trademark", "otp"
    );

    private static final List<Pattern> OTP_PATTERNS = List.of(
            Pattern.compile("\\b(\\d{6})\\b"),
            Pattern.compile("OTP[:\\s]+(\\d{4,6})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("code[:\\s]+(\\d{4,6})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("verification[:\\s]+(\\d{4,6})", Pattern.CASE_INSENSITIVE)
    );

    // Default OTP timeout — 360s handles slow Hostinger delivery
    private static final int DEFAULT_TIMEOUT_SECS      = 360;
    // Auto-resend trigger if nothing arrives after this many seconds
    private static final int RESEND_TRIGGER_SECS        = 90;
    private static final int POLL_INTERVAL_SECS         = 5;
    // How far back to look for OTP emails
    private static final int CUTOFF_LOOKBACK_MINUTES    = 5;

    private final ScraperSettings settings;

    @Autowired
    public OtpReaderService(ScraperSettings settings) {
        this.settings = settings;
    }

    /**
     * Wait for OTP. Uses default 360s timeout and 5-minute lookback window.
     *
     * @param emailAddress    email to check (null = settings default)
     * @param password        IMAP password (null = settings default)
     * @param timeoutSecs     max wait (use 0 for DEFAULT_TIMEOUT_SECS = 360)
     * @param pollIntervalSecs seconds between inbox polls
     * @return the OTP string
     */
    public String waitForOtp(
            String emailAddress,
            String password,
            int timeoutSecs,
            int pollIntervalSecs) throws InterruptedException {

        return waitForOtp(emailAddress, password, timeoutSecs, pollIntervalSecs, null);
    }

    /**
     * Extended overload that accepts a resendCallback.
     * The callback is invoked once when RESEND_TRIGGER_SECS elapses without an OTP.
     * The LoginService passes a lambda that clicks "Resend OTP" on the browser page.
     */
    public String waitForOtp(
            String emailAddress,
            String password,
            int timeoutSecs,
            int pollIntervalSecs,
            Runnable resendCallback) throws InterruptedException {

        String email   = emailAddress != null ? emailAddress : settings.getEmailAddress();
        String pass    = password     != null ? password     : settings.getEmailAppPassword();
        String imap    = settings.getImapServer();
        int    timeout = timeoutSecs > 0 ? timeoutSecs : DEFAULT_TIMEOUT_SECS;
        int    poll    = pollIntervalSecs > 0 ? pollIntervalSecs : POLL_INTERVAL_SECS;

        Instant start   = Instant.now();
        Instant cutoff  = start.minus(Duration.ofMinutes(CUTOFF_LOOKBACK_MINUTES));
        boolean resendFired = false;

        log.info("Waiting for OTP (email={}, timeout={}s, resend_at={}s)...",
                email, timeout, RESEND_TRIGGER_SECS);

        // Open a persistent IMAP store for the whole wait loop — avoids reconnect every 5s
        // Open a persistent IMAP store for the whole wait loop — avoids reconnect every 5s
        Store store = null;
        try {
            store = openStore(email, pass, imap);
        } catch (Exception e) {
            log.warn("Initial IMAP connect failed (will retry in loop): {}", e.getMessage());
        }

        try {
            while (Duration.between(start, Instant.now()).getSeconds() < timeout) {

                // ── Auto-resend trigger ──────────────────────────────────────
                long elapsed = Duration.between(start, Instant.now()).getSeconds();
                if (!resendFired && elapsed >= RESEND_TRIGGER_SECS && resendCallback != null) {
                    log.info("OTP not received after {}s — triggering resend", RESEND_TRIGGER_SECS);
                    try { resendCallback.run(); } catch (Exception re) {
                        log.warn("Resend callback threw: {}", re.getMessage());
                    }
                    resendFired = true;
                    // Update cutoff so we don't pick up the pre-resend OTP
                    cutoff = Instant.now().minus(Duration.ofSeconds(10));
                }

                // ── Poll inbox ───────────────────────────────────────────────
                try {
                    if (store == null || !store.isConnected()) {
                        store = openStore(email, pass, imap);
                    }
                    Optional<String> otp = pollInbox(store, cutoff);
                    if (otp.isPresent()) {
                        log.info("OTP received after {}s: {}", elapsed, otp.get());
                        return otp.get();
                    }
                } catch (Exception e) {
                    log.warn("IMAP poll error (will retry): {}", e.getMessage());
                    // Force reconnect on next iteration
                    try { if (store != null) store.close(); } catch (Exception ignored) {}
                    store = null;
                    Thread.sleep(8000);
                    continue;
                }

                Thread.sleep(poll * 1000L);
            }

        } finally {
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }

        throw new RuntimeException(
                "OTP not received within " + timeout + "s for " + email +
                ". Check IMAP connectivity and that the OTP email was sent.");
    }

    /** Convenience overload — 360s timeout, no resend callback */
    public String waitForOtp(int timeoutSecs) throws InterruptedException {
        return waitForOtp(null, null, timeoutSecs, POLL_INTERVAL_SECS, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMAP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Store openStore(String email, String pass, String imapServer) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol",             "imaps");
        props.put("mail.imaps.host",                 imapServer);
        props.put("mail.imaps.port",                 "993");
        props.put("mail.imaps.ssl.enable",           "true");
        props.put("mail.imaps.connectiontimeout",    "20000");  // 20s connect
        props.put("mail.imaps.timeout",              "20000");  // 20s read
        props.put("mail.imaps.writetimeout",         "20000");  // 20s write
        props.put("mail.imaps.connectionpoolsize",   "1");
        props.put("mail.imaps.connectionpooltimeout","15000");

        Exception lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Session session = Session.getInstance(props);
                Store store = session.getStore("imaps");
                store.connect(imapServer, email, pass);
                log.debug("IMAP connected to {} (attempt {})", imapServer, attempt);
                return store;
            } catch (Exception e) {
                lastEx = e;
                log.warn("IMAP connect attempt {}/3 failed for {}: {}", attempt, email, e.getMessage());
                if (attempt < 3) {
                    Thread.sleep(6000L * attempt); // 6s, 12s backoff
                }
            }
        }
        throw lastEx != null ? lastEx : new RuntimeException("IMAP connect failed for " + email);
    }

    private Optional<String> pollInbox(Store store, Instant cutoff) throws Exception {
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        try {
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            if (messages.length == 0) {
                messages = inbox.getMessages();
            }

            int startIdx = Math.max(0, messages.length - 15);
            for (int i = messages.length - 1; i >= startIdx; i--) {
                Message msg = messages[i];
                String sender  = msg.getFrom() != null ? msg.getFrom()[0].toString().toLowerCase() : "";
                String subject = msg.getSubject() != null ? msg.getSubject() : "";

                if (!isOtpEmail(sender, subject)) continue;

                if (msg.getSentDate() != null) {
                    Instant sentAt = msg.getSentDate().toInstant();
                    if (sentAt.isBefore(cutoff)) continue;
                }

                String body = getEmailBody(msg);
                Optional<String> otp = extractOtp(body);
                if (otp.isPresent()) {
                    msg.setFlag(Flags.Flag.SEEN, true);
                    log.info("OTP found in email from '{}' subject '{}': {}", sender, subject, otp.get());
                    return otp;
                }
            }
            return Optional.empty();
        } finally {
            try { inbox.close(false); } catch (Exception ignored) {}
        }
    }

    private boolean isOtpEmail(String sender, String subject) {
        String combined = (sender + " " + subject).toLowerCase();
        return SENDER_PATTERNS.stream().anyMatch(combined::contains);
    }

    private Optional<String> extractOtp(String body) {
        for (Pattern p : OTP_PATTERNS) {
            Matcher m = p.matcher(body);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    private String getEmailBody(Message msg) throws Exception {
        if (msg.isMimeType("text/plain")) {
            return (String) msg.getContent();
        }
        if (msg.isMimeType("multipart/*")) {
            MimeMultipart mp = (MimeMultipart) msg.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                if (part.isMimeType("text/plain")) sb.append(part.getContent());
            }
            if (sb.length() > 0) return sb.toString();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                if (part.isMimeType("text/html")) return (String) part.getContent();
            }
        }
        return msg.getContent().toString();
    }
}
