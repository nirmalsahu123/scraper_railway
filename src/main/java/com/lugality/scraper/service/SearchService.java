package com.lugality.scraper.service;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.workflow.ScraperState;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SearchService {

    private static final int MAX_CAPTCHA_RETRIES = 5;

    private final ScraperSettings settings;
    private final LoginService loginService;

    @Autowired
    public SearchService(ScraperSettings settings, LoginService loginService) {
        this.settings = settings;
        this.loginService = loginService;
    }

    public ScraperState search(ScraperState state, BrowserManager browser) {
        try {
            if (state.getApplicationsQueue().isEmpty()) {
                state.setCurrentStep(ScraperState.Step.COMPLETED);
                return state;
            }

            // Detect dead Playwright context BEFORE it throws opaque Error {}
            if (!browser.isAlive()) {
                log.error("Browser context is dead before search — needs recycle");
                state.setCurrentStep(ScraperState.Step.ERROR);
                state.setErrorMessage("Browser context dead — recycle required");
                return state;
            }

            String appNumber = state.getApplicationsQueue().get(0);
            state.setCurrentApplication(appNumber);
            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
            log.info("Searching for application: {}", appNumber);

            Page page = browser.getPage();

            ElementHandle appInput = findApplicationInput(page);
            if (appInput == null) throw new RuntimeException("Could not find application number input");

            safeFill(appInput, "");
            safeFill(appInput, appNumber);

            for (int attempt = 1; attempt <= MAX_CAPTCHA_RETRIES; attempt++) {
                state.setCurrentStep(ScraperState.Step.SOLVING_SEARCH_CAPTCHA);

                int captchaAnswer = loginService.solvePageCaptcha(browser);
                log.info("Search CAPTCHA solved (attempt {}/{}): {}", attempt, MAX_CAPTCHA_RETRIES, captchaAnswer);

                ElementHandle captchaInput = findCaptchaInput(page);
                if (captchaInput == null) throw new RuntimeException("Could not find CAPTCHA input");
                safeFill(captchaInput, String.valueOf(captchaAnswer));

                if (!clickViewButton(page)) throw new RuntimeException("Could not click View button");
                Thread.sleep(3000);

                if (checkInvalidCaptcha(page)) {
                    if (attempt < MAX_CAPTCHA_RETRIES) {
                        log.warn("Invalid CAPTCHA '{}' for {} (attempt {}/{}), retrying...",
                                captchaAnswer, appNumber, attempt, MAX_CAPTCHA_RETRIES);
                        refreshCaptcha(page);
                        appInput = findApplicationInput(page);
                        if (appInput != null) {
                            String current = appInput.inputValue();
                            if (!appNumber.equals(current)) {
                                safeFill(appInput, "");
                                safeFill(appInput, appNumber);
                            }
                        }
                        Thread.sleep(2000);
                        continue;
                    } else {
                        browser.screenshot("captcha_failed_" + appNumber);
                        state.setErrorMessage("CAPTCHA failed after " + MAX_CAPTCHA_RETRIES + " attempts");
                        return state;
                    }
                }

                if (checkResultsLoaded(page)) {
                    log.info("Results loaded for application {}", appNumber);
                    state.setCurrentStep(ScraperState.Step.EXTRACTING_DATA);
                    state.setErrorMessage(null);
                    return state;
                }

                if (page.querySelector("text=No record found") != null ||
                        page.querySelector("text=No matching records") != null) {
                    log.warn("No results found for {}", appNumber);
                    state.setErrorMessage("No records found");
                    return state;
                }

                if (attempt < MAX_CAPTCHA_RETRIES) {
                    log.warn("Results unclear for {} (attempt {}), retrying...", appNumber, attempt);
                    browser.screenshot("search_retry_" + appNumber + "_" + attempt);
                    refreshCaptcha(page);
                    Thread.sleep(2000);
                } else {
                    browser.screenshot("search_issue_" + appNumber);
                    state.setErrorMessage("Results page did not load properly");
                    return state;
                }
            }

            state.setErrorMessage("Search failed unexpectedly");
            return state;

        } catch (Exception e) {
            log.error("Search error for {}: {}", state.getCurrentApplication(), e.getMessage());
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage("Search error: " + e.getMessage());
            browser.screenshot("search_error_" + state.getCurrentApplication());
            return state;
        }
    }

    public ScraperState nextApplication(ScraperState state, BrowserManager browser) {
        String current = state.getCurrentApplication();

        if (current != null) {
            state.getApplicationsQueue().remove(current);

            if (state.getErrorMessage() != null) {
                state.getFailedApplications().add(java.util.Map.of(
                        "application_number", current,
                        "error", state.getErrorMessage(),
                        "retry_count", state.getRetryCount()
                ));
            } else {
                state.getProcessedApplications().add(current);
            }

            state.setProcessedCount(state.getProcessedCount() + 1);
        }

        state.setCurrentApplication(null);
        state.setExtractedData(null);
        state.setPdfDocuments(new java.util.ArrayList<>());
        state.setErrorMessage(null);
        state.setRetryCount(0);

        try {
            log.info("Waiting {}s before next application...", settings.getDelayBetweenSearches());
            Thread.sleep(settings.getDelayBetweenSearches() * 1000L);
        } catch (InterruptedException ignored) {}

        navigateBackToSearchForm(browser);

        if (!state.getApplicationsQueue().isEmpty()) {
            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
            log.info("Moving to next application. {} remaining.", state.getApplicationsQueue().size());
        } else {
            state.setCurrentStep(ScraperState.Step.COMPLETED);
            log.info("All applications processed!");
        }

        return state;
    }

    // ─────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────

    public ScraperState navigateToSearch(ScraperState state, BrowserManager browser) {
        try {
            log.info("Navigating to search section...");
            state.setCurrentStep(ScraperState.Step.NAVIGATING_TO_SEARCH);
            Page page = browser.getPage();

            // Step 1: Force close any modal
            try {
                page.evaluate("() => {" +
                        "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
                        "document.body.classList.remove('modal-open');" +
                        "document.body.style.overflow='';" +
                        "}");
            } catch (Exception ignored) {}

            // ✅ FIX: Wait for global-loader to disappear before clicking
            waitForLoaderToHide(page, 15000);

            Thread.sleep(1000);

            try {
                page.click("text=Trade Mark Application/Registered Mark",
                        new Page.ClickOptions().setTimeout(10000));
            } catch (Exception e1) {
                try {
                    page.click("text=Trade Mark Application",
                            new Page.ClickOptions().setTimeout(5000));
                } catch (Exception e2) {
                    for (ElementHandle el : page.querySelectorAll("a, button, div[onclick]")) {
                        String text = el.textContent();
                        if (text != null && text.toLowerCase().contains("trade mark") &&
                                text.toLowerCase().contains("application")) {
                            el.click();
                            break;
                        }
                    }
                }
            }

            Thread.sleep(2000);
            log.info("Navigated to Trade Mark section");
        } catch (Exception e) {
            log.error("Navigation error: {}", e.getMessage());
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage("Navigation error: " + e.getMessage());
            browser.screenshot("navigation_error");
        }
        return state;
    }

    public ScraperState selectNationalIrdi(ScraperState state, BrowserManager browser) {
        try {
            log.info("Selecting National/IRDI Number option...");
            state.setCurrentStep(ScraperState.Step.SELECTING_SEARCH_TYPE);
            Page page = browser.getPage();

            // Step 1: Force close any modal
            try {
                page.evaluate("() => {" +
                        "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
                        "document.body.classList.remove('modal-open');" +
                        "document.body.style.overflow='';" +
                        "}");
                Thread.sleep(300);
            } catch (Exception ignored) {}

            // ✅ FIX: Wait for global-loader to disappear before clicking radio
            waitForLoaderToHide(page, 10000);

            browser.screenshot("before_radio_select");
            boolean clicked = false;

            // Method 1: Exact ID
            try {
                ElementHandle radio = page.querySelector("#NationalIRDINumber");
                if (radio != null) { radio.click(); clicked = true; log.info("Clicked #NationalIRDINumber"); }
            } catch (Exception ignored) {}

            // Method 2: name/value
            if (!clicked) {
                try {
                    ElementHandle radio = page.querySelector("input[name='Location'][value='National']");
                    if (radio != null) { radio.click(); clicked = true; }
                } catch (Exception ignored) {}
            }

            // Method 3: Label text — increased timeout to 15s
            if (!clicked) {
                try {
                    page.click("text=National/IRDI Number", new Page.ClickOptions().setTimeout(15000));
                    clicked = true;
                    log.info("Clicked via text=National/IRDI Number");
                } catch (Exception ignored) {}
            }

            // Method 4: JS click on any radio whose label contains National
            if (!clicked) {
                try {
                    page.evaluate("() => {" +
                            "const radios = document.querySelectorAll('input[type=radio]');" +
                            "for (const r of radios) {" +
                            "  const label = document.querySelector('label[for=\"' + r.id + '\"]');" +
                            "  if (label && label.textContent.toLowerCase().includes('national')) {" +
                            "    r.click(); return true;" +
                            "  }" +
                            "  if (r.value && r.value.toLowerCase().includes('national')) {" +
                            "    r.click(); return true;" +
                            "  }" +
                            "}" +
                            "return false;" +
                            "}");
                    clicked = true;
                    log.info("Clicked National radio via JS fallback");
                } catch (Exception ignored) {}
            }

            if (!clicked) {
                browser.screenshot("radio_not_found");
                throw new RuntimeException("Could not click National/IRDI radio button — all 4 methods failed");
            }

            Thread.sleep(1000);

            // Submit form via JS
            page.evaluate("() => { const f = document.getElementById('myform'); if (f) f.submit(); }");

            // Wait for loader after form submit before proceeding
            waitForLoaderToHide(page, 15000);

            Thread.sleep(1000);

            browser.screenshot("after_form_submit");

            List<String> inputSelectors = List.of(
                    "input[placeholder*='Number']",
                    "input[placeholder*='Enter']",
                    "input[name*='Number']",
                    "input[name*='Application']"
            );

            boolean formReady = false;
            for (int i = 0; i < 8; i++) {
                for (String sel : inputSelectors) {
                    ElementHandle inp = page.querySelector(sel);
                    if (inp != null && inp.isVisible()) { formReady = true; break; }
                }
                if (formReady) break;
                Thread.sleep(2000);
            }

            if (!formReady) {
                // Hard failure — form didn't load; mark error so orchestrator skips search()
                browser.screenshot("form_not_ready");
                throw new RuntimeException("Search form input not found after form submit — page may not have loaded");
            }
            log.info("National/IRDI option selected, search form ready");
            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);

        } catch (Exception e) {
            log.error("Selection error: {}", e.getMessage());
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage("Search type selection error: " + e.getMessage());
            browser.screenshot("selection_error");
        }
        return state;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * ✅ NEW: Wait for #global-loader to disappear before interacting with page.
     * Prevents "intercepts pointer events" errors.
     */
    private void waitForLoaderToHide(Page page, int timeoutMs) {
        try {
            page.waitForFunction(
                    "() => {" +
                            "  const loader = document.getElementById('global-loader');" +
                            "  if (!loader) return true;" +
                            "  const style = window.getComputedStyle(loader);" +
                            "  return style.display === 'none' || " +
                            "         style.visibility === 'hidden' || " +
                            "         style.opacity === '0' || " +
                            "         loader.classList.contains('d-none') || " +
                            "         loader.classList.contains('hide') || " +
                            "         !loader.offsetParent;" +
                            "}",
                    new Page.WaitForFunctionOptions().setTimeout(timeoutMs)
            );
            log.debug("global-loader hidden, proceeding");
        } catch (Exception ignored) {
            // Loader didn't hide in time — proceed anyway
            log.debug("global-loader wait timed out — proceeding anyway");
        }
    }

    private ElementHandle findApplicationInput(Page page) {
        List<String> selectors = List.of(
                "input[placeholder='Enter Number']",
                "input[placeholder*='Number']",
                "input[name*='Application']",
                "input[name*='Number']",
                "input[id*='Application']",
                "input[id*='Number']"
        );
        for (int attempt = 0; attempt < 5; attempt++) {
            for (String sel : selectors) {
                ElementHandle el = page.querySelector(sel);
                if (el != null && el.isVisible()) return el;
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private ElementHandle findCaptchaInput(Page page) {
        List<String> selectors = List.of(
                "input[placeholder='Enter answer']",
                "input[placeholder*='answer']",
                "input[name*='Captcha']",
                "input[id*='Captcha']"
        );
        for (String sel : selectors) {
            ElementHandle el = page.querySelector(sel);
            if (el != null && el.isVisible()) return el;
        }
        List<ElementHandle> inputs = page.querySelectorAll("input[type='text']");
        return inputs.size() >= 2 ? inputs.get(1) : null;
    }

    private boolean clickViewButton(Page page) {
        List<String> selectors = List.of(
                "input[value='View']",
                "button:has-text('View')",
                "input[type='submit'][value='View']",
                "button[type='submit']"
        );
        for (String sel : selectors) {
            ElementHandle el = page.querySelector(sel);
            if (el != null) { el.click(); return true; }
        }
        return false;
    }

    private boolean checkInvalidCaptcha(Page page) {
        try {
            List<String> errorSelectors = List.of(
                    "text=Invalid Captcha", "text=invalid captcha",
                    "text=Wrong Captcha",   ".text-danger:has-text('Captcha')"
            );
            for (String sel : errorSelectors) {
                if (page.querySelector(sel) != null) return true;
            }
            String content = page.content();
            return content.contains("Invalid Captcha") &&
                    (content.contains("Enter the captcha Code") || content.contains("Enter answer"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkResultsLoaded(Page page) {
        try {
            ElementHandle table = page.querySelector("table");
            if (table != null && table.querySelectorAll("tr").size() > 1) return true;

            List<String> indicators = List.of(
                    "text=Matching Trade Marks", "text=Trade Mark Image",
                    "text=Uploaded Documents",   "text=PR Details"
            );
            for (String ind : indicators) {
                if (page.querySelector(ind) != null) return true;
            }

            String content = page.content();
            return List.of("Matching Trade Marks", "Trade Mark No",
                            "Date of Application", "Proprietor Name")
                    .stream().anyMatch(content::contains);
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshCaptcha(Page page) {
        List<String> selectors = List.of(
                "a[title='Refresh']", "a[title='Refresh Captcha']",
                ".refresh-captcha", "button:has-text('Refresh')"
        );
        for (String sel : selectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible()) {
                    btn.click();
                    Thread.sleep(2000);
                    return;
                }
            } catch (Exception ignored) {}
        }
        log.debug("No CAPTCHA refresh button found");
    }

    private void safeFill(ElementHandle element, String value) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (element.isEditable()) { element.fill(value); return; }
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        element.fill(value);
    }

    private void navigateBackToSearchForm(BrowserManager browser) {
        Page page = browser.getPage();
        try {
            page.evaluate("() => {" +
                    "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
                    "document.body.classList.remove('modal-open');" +
                    "document.body.style.overflow='';" +
                    "}");
            Thread.sleep(500);
        } catch (Exception ignored) {}
        try {
            page.navigate(settings.getTargetUrl(),
                    new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30000));
            // ✅ FIX: Wait for loader after page load too
            waitForLoaderToHide(page, 15000);
            Thread.sleep(1000);
            log.info("Navigated back via direct URL");
        } catch (Exception e) {
            log.warn("Direct navigation failed: {}", e.getMessage());
        }
    }
}












//package com.lugality.scraper.service;
//
//import com.lugality.scraper.config.ScraperSettings;
//import com.lugality.scraper.workflow.ScraperState;
//import com.microsoft.playwright.*;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Optional;
//
///**
// * Handles trademark application search on IP India portal.
// * Equivalent to Python's search.py (search_application_node).
// *
// * Steps:
// *  1. Enter application number
// *  2. Solve search CAPTCHA (with retries)
// *  3. Click View
// *  4. Verify results loaded
// */
//@Slf4j
//@Service
//public class SearchService {
//
//    private static final int MAX_CAPTCHA_RETRIES = 5;
//
//    private final ScraperSettings settings;
//    private final LoginService loginService; // reuse captcha solver
//
//    @Autowired
//    public SearchService(ScraperSettings settings, LoginService loginService) {
//        this.settings = settings;
//        this.loginService = loginService;
//    }
//
//    /**
//     * Search for an application number.
//     * Equivalent to Python's search_application_node().
//     */
//    public ScraperState search(ScraperState state, BrowserManager browser) {
//        try {
//            if (state.getApplicationsQueue().isEmpty()) {
//                state.setCurrentStep(ScraperState.Step.COMPLETED);
//                return state;
//            }
//
//            String appNumber = state.getApplicationsQueue().get(0);
//            state.setCurrentApplication(appNumber);
//            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
//            log.info("Searching for application: {}", appNumber);
//
//            Page page = browser.getPage();
//
//            // 1. Find application input
//            ElementHandle appInput = findApplicationInput(page);
//            if (appInput == null) throw new RuntimeException("Could not find application number input");
//
//            safeFill(appInput, "");
//            safeFill(appInput, appNumber);
//
//            // 2. CAPTCHA retry loop
//            for (int attempt = 1; attempt <= MAX_CAPTCHA_RETRIES; attempt++) {
//                state.setCurrentStep(ScraperState.Step.SOLVING_SEARCH_CAPTCHA);
//
//                int captchaAnswer = loginService.solvePageCaptcha(browser);
//                log.info("Search CAPTCHA solved (attempt {}/{}): {}", attempt, MAX_CAPTCHA_RETRIES, captchaAnswer);
//
//                ElementHandle captchaInput = findCaptchaInput(page);
//                if (captchaInput == null) throw new RuntimeException("Could not find CAPTCHA input");
//                safeFill(captchaInput, String.valueOf(captchaAnswer));
//
//                // 3. Click View
//                if (!clickViewButton(page)) throw new RuntimeException("Could not click View button");
//                Thread.sleep(3000);
//
//                // 4. Check for invalid CAPTCHA
//                if (checkInvalidCaptcha(page)) {
//                    if (attempt < MAX_CAPTCHA_RETRIES) {
//                        log.warn("Invalid CAPTCHA '{}' for {} (attempt {}/{}), retrying...",
//                                captchaAnswer, appNumber, attempt, MAX_CAPTCHA_RETRIES);
//                        refreshCaptcha(page);
//                        // Re-fill app number if cleared
//                        appInput = findApplicationInput(page);
//                        if (appInput != null) {
//                            String current = appInput.inputValue();
//                            if (!appNumber.equals(current)) {
//                                safeFill(appInput, "");
//                                safeFill(appInput, appNumber);
//                            }
//                        }
//                        Thread.sleep(2000);
//                        continue;
//                    } else {
//                        browser.screenshot("captcha_failed_" + appNumber);
//                        state.setErrorMessage("CAPTCHA failed after " + MAX_CAPTCHA_RETRIES + " attempts");
//                        return state;
//                    }
//                }
//
//                // 5. Check results
//                if (checkResultsLoaded(page)) {
//                    log.info("Results loaded for application {}", appNumber);
//                    state.setCurrentStep(ScraperState.Step.EXTRACTING_DATA);
//                    state.setErrorMessage(null);
//                    return state;
//                }
//
//                // No results and no CAPTCHA error
//                if (page.querySelector("text=No record found") != null ||
//                    page.querySelector("text=No matching records") != null) {
//                    log.warn("No results found for {}", appNumber);
//                    state.setErrorMessage("No records found");
//                    return state;
//                }
//
//                if (attempt < MAX_CAPTCHA_RETRIES) {
//                    log.warn("Results unclear for {} (attempt {}), retrying...", appNumber, attempt);
//                    browser.screenshot("search_retry_" + appNumber + "_" + attempt);
//                    refreshCaptcha(page);
//                    Thread.sleep(2000);
//                } else {
//                    browser.screenshot("search_issue_" + appNumber);
//                    state.setErrorMessage("Results page did not load properly");
//                    return state;
//                }
//            }
//
//            state.setErrorMessage("Search failed unexpectedly");
//            return state;
//
//        } catch (Exception e) {
//            log.error("Search error for {}: {}", state.getCurrentApplication(), e.getMessage());
//            state.setCurrentStep(ScraperState.Step.ERROR);
//            state.setErrorMessage("Search error: " + e.getMessage());
//            browser.screenshot("search_error_" + state.getCurrentApplication());
//            return state;
//        }
//    }
//
//    /**
//     * Move to the next application and navigate back to search form.
//     * Equivalent to Python's next_application_node().
//     */
//    public ScraperState nextApplication(ScraperState state, BrowserManager browser) {
//        String current = state.getCurrentApplication();
//
//        if (current != null) {
//            state.getApplicationsQueue().remove(current);
//
//            if (state.getErrorMessage() != null) {
//                state.getFailedApplications().add(java.util.Map.of(
//                        "application_number", current,
//                        "error", state.getErrorMessage(),
//                        "retry_count", state.getRetryCount()
//                ));
//            } else {
//                state.getProcessedApplications().add(current);
//            }
//
//            state.setProcessedCount(state.getProcessedCount() + 1);
//        }
//
//        // Reset
//        state.setCurrentApplication(null);
//        state.setExtractedData(null);
//        state.setPdfDocuments(new java.util.ArrayList<>());
//        state.setErrorMessage(null);
//        state.setRetryCount(0);
//
//        // Rate limiting delay
//        try {
//            log.info("Waiting {}s before next application...", settings.getDelayBetweenSearches());
//            Thread.sleep(settings.getDelayBetweenSearches() * 1000L);
//        } catch (InterruptedException ignored) {}
//
//        // Navigate back to search form
//        navigateBackToSearchForm(browser);
//
//        if (!state.getApplicationsQueue().isEmpty()) {
//            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
//            log.info("Moving to next application. {} remaining.", state.getApplicationsQueue().size());
//        } else {
//            state.setCurrentStep(ScraperState.Step.COMPLETED);
//            log.info("All applications processed!");
//        }
//
//        return state;
//    }
//
//    // ─────────────────────────────────────────────────────────────
//    // Navigation
//    // ─────────────────────────────────────────────────────────────
//
//    /**
//     * Navigate to Trade Mark Application section.
//     * Equivalent to Python's navigate_to_search_node().
//     */
//    public ScraperState navigateToSearch(ScraperState state, BrowserManager browser) {
//        try {
//            log.info("Navigating to search section...");
//            state.setCurrentStep(ScraperState.Step.NAVIGATING_TO_SEARCH);
//            Page page = browser.getPage();
//            // Pehle modal force close karo
//            try {
//                page.evaluate("() => {" +
//                        "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
//                        "document.body.classList.remove('modal-open');" +
//                        "document.body.style.overflow='';" +
//                        "}");
//            } catch (Exception ignored) {}
////            Thread.sleep(1000);
//            Thread.sleep(1000);
//
//            try {
//                page.click("text=Trade Mark Application/Registered Mark",
//                        new Page.ClickOptions().setTimeout(10000));
//            } catch (Exception e1) {
//                try {
//                    page.click("text=Trade Mark Application",
//                            new Page.ClickOptions().setTimeout(5000));
//                } catch (Exception e2) {
//                    // Search all clickable elements
//                    for (ElementHandle el : page.querySelectorAll("a, button, div[onclick]")) {
//                        String text = el.textContent();
//                        if (text != null && text.toLowerCase().contains("trade mark") &&
//                                text.toLowerCase().contains("application")) {
//                            el.click();
//                            break;
//                        }
//                    }
//                }
//            }
//
//            Thread.sleep(2000);
//            log.info("Navigated to Trade Mark section");
//        } catch (Exception e) {
//            log.error("Navigation error: {}", e.getMessage());
//            state.setCurrentStep(ScraperState.Step.ERROR);
//            state.setErrorMessage("Navigation error: " + e.getMessage());
//            browser.screenshot("navigation_error");
//        }
//        return state;
//    }
//
//    /**
//     * Select National/IRDI Number radio and submit form.
//     * Equivalent to Python's select_national_irdi_node().
//     */
//    public ScraperState selectNationalIrdi(ScraperState state, BrowserManager browser) {
//        try {
////            log.info("Selecting National/IRDI Number option...");
////            state.setCurrentStep(ScraperState.Step.SELECTING_SEARCH_TYPE);
////            Page page = browser.getPage();
////
////            browser.screenshot("before_radio_select");
//            log.info("Selecting National/IRDI Number option...");
//            state.setCurrentStep(ScraperState.Step.SELECTING_SEARCH_TYPE);
//            Page page = browser.getPage();
//// Modal force close
//            try {
//                page.evaluate("() => {" +
//                        "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
//                        "document.body.classList.remove('modal-open');" +
//                        "document.body.style.overflow='';" +
//                        "}");
//                Thread.sleep(300);
//            } catch (Exception ignored) {}
//
//            browser.screenshot("before_radio_select");
//            boolean clicked = false;
//
//            // Method 1: Exact ID
//            try {
//                ElementHandle radio = page.querySelector("#NationalIRDINumber");
//                if (radio != null) { radio.click(); clicked = true; log.info("Clicked #NationalIRDINumber"); }
//            } catch (Exception ignored) {}
//
//            // Method 2: name/value
//            if (!clicked) {
//                try {
//                    ElementHandle radio = page.querySelector("input[name='Location'][value='National']");
//                    if (radio != null) { radio.click(); clicked = true; }
//                } catch (Exception ignored) {}
//            }
//
//            // Method 3: Label text
//            if (!clicked) {
//                page.click("text=National/IRDI Number", new Page.ClickOptions().setTimeout(5000));
//                clicked = true;
//            }
//
//            if (!clicked) {
//                browser.screenshot("radio_not_found");
//                throw new RuntimeException("Could not click National/IRDI radio button");
//            }
//
//            Thread.sleep(1000);
//
//            // Submit form via JS
//            page.evaluate("() => { const f = document.getElementById('myform'); if (f) f.submit(); }");
//            Thread.sleep(3000);
//
//            browser.screenshot("after_form_submit");
//
//            // Verify search form loaded
//            List<String> inputSelectors = List.of(
//                    "input[placeholder*='Number']",
//                    "input[placeholder*='Enter']",
//                    "input[name*='Number']",
//                    "input[name*='Application']"
//            );
//
//            boolean formReady = false;
//            for (int i = 0; i < 5; i++) {
//                for (String sel : inputSelectors) {
//                    ElementHandle inp = page.querySelector(sel);
//                    if (inp != null && inp.isVisible()) { formReady = true; break; }
//                }
//                if (formReady) break;
//                Thread.sleep(2000);
//            }
//
//            if (!formReady) log.warn("Search form input not found — proceeding anyway");
//            log.info("National/IRDI option selected, search form ready");
//            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
//
//        } catch (Exception e) {
//            log.error("Selection error: {}", e.getMessage());
//            state.setCurrentStep(ScraperState.Step.ERROR);
//            state.setErrorMessage("Search type selection error: " + e.getMessage());
//            browser.screenshot("selection_error");
//        }
//        return state;
//    }
//
//    // ─────────────────────────────────────────────────────────────
//    // Private helpers
//    // ─────────────────────────────────────────────────────────────
//
//    private ElementHandle findApplicationInput(Page page) {
//        List<String> selectors = List.of(
//                "input[placeholder='Enter Number']",
//                "input[placeholder*='Number']",
//                "input[name*='Application']",
//                "input[name*='Number']",
//                "input[id*='Application']",
//                "input[id*='Number']"
//        );
//        for (String sel : selectors) {
//            ElementHandle el = page.querySelector(sel);
//            if (el != null && el.isVisible()) return el;
//        }
//        return null;
//    }
//
//    private ElementHandle findCaptchaInput(Page page) {
//        List<String> selectors = List.of(
//                "input[placeholder='Enter answer']",
//                "input[placeholder*='answer']",
//                "input[name*='Captcha']",
//                "input[id*='Captcha']"
//        );
//        for (String sel : selectors) {
//            ElementHandle el = page.querySelector(sel);
//            if (el != null && el.isVisible()) return el;
//        }
//        // Fallback: second text input
//        List<ElementHandle> inputs = page.querySelectorAll("input[type='text']");
//        return inputs.size() >= 2 ? inputs.get(1) : null;
//    }
//
//    private boolean clickViewButton(Page page) {
//        List<String> selectors = List.of(
//                "input[value='View']",
//                "button:has-text('View')",
//                "input[type='submit'][value='View']",
//                "button[type='submit']"
//        );
//        for (String sel : selectors) {
//            ElementHandle el = page.querySelector(sel);
//            if (el != null) { el.click(); return true; }
//        }
//        return false;
//    }
//
//    private boolean checkInvalidCaptcha(Page page) {
//        try {
//            List<String> errorSelectors = List.of(
//                    "text=Invalid Captcha", "text=invalid captcha",
//                    "text=Wrong Captcha",   ".text-danger:has-text('Captcha')"
//            );
//            for (String sel : errorSelectors) {
//                if (page.querySelector(sel) != null) return true;
//            }
//            String content = page.content();
//            return content.contains("Invalid Captcha") &&
//                   (content.contains("Enter the captcha Code") || content.contains("Enter answer"));
//        } catch (Exception e) {
//            return false;
//        }
//    }
//
//    private boolean checkResultsLoaded(Page page) {
//        try {
//            ElementHandle table = page.querySelector("table");
//            if (table != null && table.querySelectorAll("tr").size() > 1) return true;
//
//            List<String> indicators = List.of(
//                    "text=Matching Trade Marks", "text=Trade Mark Image",
//                    "text=Uploaded Documents",   "text=PR Details"
//            );
//            for (String ind : indicators) {
//                if (page.querySelector(ind) != null) return true;
//            }
//
//            String content = page.content();
//            return List.of("Matching Trade Marks", "Trade Mark No",
//                           "Date of Application", "Proprietor Name")
//                       .stream().anyMatch(content::contains);
//        } catch (Exception e) {
//            return false;
//        }
//    }
//
//    private void refreshCaptcha(Page page) {
//        List<String> selectors = List.of(
//                "a[title='Refresh']", "a[title='Refresh Captcha']",
//                ".refresh-captcha", "button:has-text('Refresh')"
//        );
//        for (String sel : selectors) {
//            try {
//                ElementHandle btn = page.querySelector(sel);
//                if (btn != null && btn.isVisible()) {
//                    btn.click();
//                    Thread.sleep(2000);
//                    return;
//                }
//            } catch (Exception ignored) {}
//        }
//        log.debug("No CAPTCHA refresh button found");
//    }
//
//    private void safeFill(ElementHandle element, String value) {
//        long deadline = System.currentTimeMillis() + 3000;
//        while (System.currentTimeMillis() < deadline) {
//            try {
//                if (element.isEditable()) { element.fill(value); return; }
//            } catch (Exception ignored) {}
//            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
//        }
//        element.fill(value); // last attempt
//    }
//
////    private void navigateBackToSearchForm(BrowserManager browser) {
////        Page page = browser.getPage();
////        try {
////            ElementHandle back = page.querySelector("button:has-text('Back'), input[value='Back']");
////            if (back != null) { back.click(); Thread.sleep(2000); }
////
////            ElementHandle tab = page.querySelector(
////                    "a:has-text('Trade Mark Application'), a:has-text('Registered Mark'), a[href*='ViewRegistered']");
////            if (tab != null) { tab.click(); Thread.sleep(2000); }
////
////            List<String> radioSelectors = List.of(
////                    "input[type='radio'][value='National']",
////                    "input[type='radio'][id*='National']"
////            );
////            for (String sel : radioSelectors) {
////                ElementHandle radio = page.querySelector(sel);
////                if (radio != null && radio.isVisible()) { radio.click(); Thread.sleep(1000); break; }
////            }
////        } catch (Exception e) {
////            log.warn("Error navigating back to search form: {}", e.getMessage());
////        }
////    }
//    private void navigateBackToSearchForm(BrowserManager browser) {
//        Page page = browser.getPage();
//        // Force close modal via JS
//        try {
//            page.evaluate("() => {" +
//                    "document.querySelectorAll('.modal,.modal-backdrop').forEach(e=>e.remove());" +
//                    "document.body.classList.remove('modal-open');" +
//                    "document.body.style.overflow='';" +
//                    "}");
//            Thread.sleep(500);
//        } catch (Exception ignored) {}
//        // Direct URL navigate — no tab clicking
//        try {
//            page.navigate(settings.getTargetUrl(),
//                    new Page.NavigateOptions()
//                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
//                            .setTimeout(30000));
//            Thread.sleep(2000);
//            log.info("Navigated back via direct URL");
//        } catch (Exception e) {
//            log.warn("Direct navigation failed: {}", e.getMessage());
//        }
//    }
//}