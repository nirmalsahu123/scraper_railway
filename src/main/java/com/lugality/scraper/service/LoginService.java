package com.lugality.scraper.service;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.workflow.ScraperState;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LoginService {

    private static final String SEL_EMAIL_INPUT          = "#emailidentifier";
    private static final String SEL_MOBILE_INPUT         = "#mobileidentifier";
    private static final String SEL_CAPTCHA_INSTRUCTION  = "#captchatext";
    private static final String SEL_CAPTCHA_EXPRESSION   = "#CaptchModel_CaptchaNumbers";
    private static final String SEL_CAPTCHA_INPUT        = "#CaptchModel_CaptchaAnswer";
    private static final String SEL_SEND_OTP_BTN         = "#sendOtpBtn";
    private static final String SEL_OTP_INPUT            = "#otp";
    private static final String SEL_VERIFY_BTN           = "button:has-text('Verify')";
    private static final String SEL_OTP_SECTION          = "#otpSection";
    private static final String SEL_LOGOUT               = "button:has-text('Logout'), a:has-text('Logout')";

    private final ScraperSettings settings;
    private final CaptchaSolver captchaSolver;
    private final OtpReaderService otpReader;

    @Autowired
    public LoginService(ScraperSettings settings,
                        CaptchaSolver captchaSolver,
                        OtpReaderService otpReader) {
        this.settings = settings;
        this.captchaSolver = captchaSolver;
        this.otpReader = otpReader;
    }

    public ScraperState login(ScraperState state, BrowserManager browser) {
        try {
            log.info("Starting login flow...");
            state.setCurrentStep(ScraperState.Step.NAVIGATING_TO_LOGIN);

            Page page = browser.getPage();

            // FIX 1: Cookies clear karo
            try {
                page.context().clearCookies();
                log.info("Cookies cleared before login");
            } catch (Exception e) {
                log.warn("Could not clear cookies: {}", e.getMessage());
            }

            // FIX 2: Blank page pehle
            try {
                page.navigate("about:blank");
                Thread.sleep(500);
            } catch (Exception ignored) {}

            // 1. Navigate
            browser.navigate(settings.getTargetUrl());

            // FIX 3: Email field visible hone ka wait
            try {
                page.waitForSelector(SEL_EMAIL_INPUT + ", " + SEL_MOBILE_INPUT,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(15000));
            } catch (Exception e) {
                log.warn("Email field wait timeout — trying page reload");
                page.reload();
                Thread.sleep(3000);
            }

            // 2. Enter email
            log.info("Entering email: {}", state.getLoginEmail());
            ElementHandle emailInput = page.querySelector(SEL_EMAIL_INPUT);
            if (emailInput != null) {
                emailInput.fill(state.getLoginEmail());
            } else {
                ElementHandle mobileInput = page.querySelector(SEL_MOBILE_INPUT);
                if (mobileInput != null) {
                    mobileInput.fill(state.getLoginEmail());
                } else {
                    throw new RuntimeException("Could not find email or mobile input field");
                }
            }

            // 3. Solve CAPTCHA
            state.setCurrentStep(ScraperState.Step.SOLVING_LOGIN_CAPTCHA);
            int captchaAnswer = solvePageCaptcha(browser);
            log.info("CAPTCHA solved: {}", captchaAnswer);

            ElementHandle captchaInput = page.querySelector(SEL_CAPTCHA_INPUT);
            if (captchaInput == null) {
                captchaInput = page.querySelector("input[placeholder*='answer']");
            }
            if (captchaInput != null) {
                captchaInput.fill(String.valueOf(captchaAnswer));
            } else {
                throw new RuntimeException("Could not find CAPTCHA input field");
            }

            // 4. Click Send OTP
            state.setCurrentStep(ScraperState.Step.SENDING_OTP);
            log.info("Clicking Send OTP...");
            page.click(SEL_SEND_OTP_BTN);
            Thread.sleep(2000);

            // 4b. Dismiss popup
            dismissPopup(page);

            // 5. Wait for OTP (360s timeout, auto-resend at 90s via callback)
            state.setCurrentStep(ScraperState.Step.WAITING_FOR_OTP);
            log.info("Waiting for OTP email (timeout=360s, auto-resend at 90s)...");
            final Page finalPage = page;
            String otp = otpReader.waitForOtp(
                    state.getLoginEmail(),
                    state.getLoginPassword(),
                    360,
                    5,
                    () -> {
                        // Resend OTP callback — triggered after 90s if no OTP arrives
                        try {
                            log.info("Auto-resend: clicking Send OTP again...");
                            ElementHandle sendBtn = finalPage.querySelector(SEL_SEND_OTP_BTN);
                            if (sendBtn != null) {
                                sendBtn.click();
                                Thread.sleep(2000);
                                dismissPopup(finalPage);
                            }
                        } catch (Exception re) {
                            log.warn("Resend OTP click failed: {}", re.getMessage());
                        }
                    }
            );
            log.info("OTP received: {}", otp);

            // 6. Enter OTP
            state.setCurrentStep(ScraperState.Step.VERIFYING_OTP);
            try {
                page.waitForSelector(SEL_OTP_SECTION,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(5000));
            } catch (Exception ignored) {}

            ElementHandle otpInput = page.querySelector(SEL_OTP_INPUT);
            if (otpInput != null) {
                otpInput.fill(otp);
            } else {
                throw new RuntimeException("Could not find OTP input field");
            }

            dismissPopup(page);
            page.keyboard().press("Escape");
            Thread.sleep(300);

            // 7. Click Verify
            log.info("Clicking Verify button...");
            page.click(SEL_VERIFY_BTN);

            // ✅ FIX: 2 sec fixed wait ki jagah dashboard load hone ka properly wait karo
            try {
                page.waitForSelector(
                        SEL_LOGOUT + ", .nav-tabs, .tab-menu",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(15000));
                log.info("Dashboard detected after verify");
            } catch (Exception ignored) {
                // Timeout — isLoggedIn se check karega
            }

            if (isLoggedIn(page)) {
                log.info("Login successful!");
                state.setLoggedIn(true);
                state.setCurrentStep(ScraperState.Step.NAVIGATING_TO_SEARCH);
                state.setErrorMessage(null);
            } else {
                throw new RuntimeException("Login verification failed - dashboard not detected");
            }

        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage("Login error: " + e.getMessage());
            state.setLoggedIn(false);
            browser.screenshot("login_error");
        }

        return state;
    }

    public int solvePageCaptcha(BrowserManager browser) throws InterruptedException {
        Page page = browser.getPage();
        Thread.sleep(1000);
        browser.screenshot("captcha_page");

        String instruction = "";
        String expression  = "";

        ElementHandle label = page.querySelector(SEL_CAPTCHA_INSTRUCTION);
        if (label != null) {
            instruction = label.innerText().trim();
            log.info("Captcha instruction: '{}'", instruction);
        }

        ElementHandle exprInput = page.querySelector(SEL_CAPTCHA_EXPRESSION);
        if (exprInput != null) {
            expression = exprInput.inputValue().trim();
            log.info("Captcha expression: '{}'", expression);
        }

        if (expression.isEmpty()) {
            String pageText = (String) page.evaluate("() => document.body.innerText");
            java.util.regex.Matcher m;

            m = java.util.regex.Pattern.compile("(\\d+\\s*[+\\-*/×÷]\\s*\\d+\\s*=\\s*\\?)").matcher(pageText);
            if (m.find()) expression = m.group(1);

            if (expression.isEmpty()) {
                m = java.util.regex.Pattern.compile("(\\d[\\d\\s]{3,12}=\\s*\\?)").matcher(pageText);
                if (m.find()) expression = m.group(1);
            }
        }

        if (expression.isEmpty()) {
            browser.screenshot("captcha_not_found");
            throw new RuntimeException("Could not find CAPTCHA expression on page");
        }

        expression = expression.strip().replaceAll("\\s+", " ");
        log.info("Solving CAPTCHA - Instruction: '{}', Expression: '{}'", instruction, expression);

        CaptchaSolver.SolveResult result = captchaSolver.solve(instruction, expression);
        log.info("Solved {} CAPTCHA: {} → {}", result.type(), expression, result.answer());

        return result.answer();
    }

    private void dismissPopup(Page page) {
        try {
            ElementHandle btn = page.waitForSelector(
                    "button.swal2-confirm, .swal2-confirm, button:has-text('OK')",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            if (btn != null) {
                log.info("Dismissing OTP sent popup...");
                btn.click();
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {}
    }

    public boolean isLoggedIn(Page page) {
        try {
            if (page.querySelector(SEL_LOGOUT) != null) return true;
            if (page.querySelector(".nav-tabs, .tab-menu") != null) return true;
            String content = page.content();
            return content.contains("Trade Mark Application") || content.contains("Registered Mark");
        } catch (Exception e) {
            return false;
        }
    }
}


