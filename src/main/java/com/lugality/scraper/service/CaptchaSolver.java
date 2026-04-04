package com.lugality.scraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified CAPTCHA solver for IP India Trade Marks Registry.
 *
 * Handles both CAPTCHA types:
 *  1. Position-based: "Enter the third number in 3 9 6 9" → 6
 *  2. Math expression: "2 * 4 = ?"                        → 8
 *
 * Equivalent to Python's captcha_solver.py.
 */
@Slf4j
@Service
public class CaptchaSolver {

    public enum CaptchaType { POSITION_BASED, MATH_EXPRESSION, UNKNOWN }

    // Ordinal word → 0-based index (-1 = last)
    private static final Map<String, Integer> ORDINALS = Map.ofEntries(
            Map.entry("first",   0), Map.entry("1st", 0),
            Map.entry("second",  1), Map.entry("2nd", 1),
            Map.entry("third",   2), Map.entry("3rd", 2),
            Map.entry("fourth",  3), Map.entry("4th", 3),
            Map.entry("fifth",   4), Map.entry("5th", 4),
            Map.entry("sixth",   5), Map.entry("6th", 5),
            Map.entry("seventh", 6), Map.entry("7th", 6),
            Map.entry("eighth",  7), Map.entry("8th", 7),
            Map.entry("ninth",   8), Map.entry("9th", 8),
            Map.entry("tenth",   9), Map.entry("10th",9),
            Map.entry("last",   -1)
    );

    public record SolveResult(int answer, CaptchaType type) {}

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Unified solver — detects type and delegates.
     * Equivalent to Python's solve_captcha().
     */
    public SolveResult solve(String instruction, String expression) {
        CaptchaType type = detect(instruction, expression);

        return switch (type) {
            case POSITION_BASED -> new SolveResult(solvePosition(instruction, expression), type);
            case MATH_EXPRESSION -> new SolveResult(solveMath(expression), type);
            case UNKNOWN -> tryBoth(instruction, expression);
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Detection
    // ─────────────────────────────────────────────────────────────

    public CaptchaType detect(String instruction, String expression) {
        String inst = instruction  == null ? "" : instruction.toLowerCase();
        String expr = expression   == null ? "" : expression.toLowerCase();

        boolean hasOrdinal = ORDINALS.keySet().stream().anyMatch(inst::contains);
        boolean hasPositionKeyword = inst.contains("number in") ||
                                     inst.contains("digit in")  ||
                                     inst.contains("enter the");

        if (hasOrdinal && hasPositionKeyword) return CaptchaType.POSITION_BASED;

        boolean hasMathKeyword = inst.contains("evaluate") || inst.contains("expression") ||
                                  inst.contains("calculate") || inst.contains("solve")    ||
                                  inst.contains("answer")    || inst.contains("what is");
        boolean hasOperator    = expr.matches(".*[+\\-*/×÷xX].*");

        if (hasMathKeyword || (hasOperator && !hasOrdinal)) return CaptchaType.MATH_EXPRESSION;

        // Fallback: pattern matching on expression
        if (expression != null) {
            if (expression.matches(".*\\d+\\s*[+\\-*/×÷xX]\\s*\\d+.*")) return CaptchaType.MATH_EXPRESSION;
            if (expression.matches(".*(\\d\\s+){2,}\\d.*"))               return CaptchaType.POSITION_BASED;
        }

        return CaptchaType.UNKNOWN;
    }

    // ─────────────────────────────────────────────────────────────
    // Position-based solver
    // ─────────────────────────────────────────────────────────────

    /**
     * Equivalent to Python's solve_position_captcha().
     *
     * Example: instruction="Enter the third number in", numbers="3 9 6 9 = ?" → 6
     */
    public int solvePosition(String instruction, String numbers) {
        if (instruction == null || numbers == null)
            throw new IllegalArgumentException("Instruction or numbers is null");

        String inst = instruction.toLowerCase();

        // Find ordinal position
        Integer position = null;
        for (Map.Entry<String, Integer> entry : ORDINALS.entrySet()) {
            if (inst.contains(entry.getKey())) {
                position = entry.getValue();
                break;
            }
        }
        if (position == null)
            throw new IllegalArgumentException("Cannot find ordinal in instruction: " + instruction);

        // Extract digits before the "=" sign
        String beforeEquals = numbers.split("=")[0];
        Matcher m = Pattern.compile("\\d").matcher(beforeEquals);
        java.util.List<Integer> digits = new java.util.ArrayList<>();
        while (m.find()) digits.add(Integer.parseInt(m.group()));

        if (digits.isEmpty())
            throw new IllegalArgumentException("No digits found in: " + numbers);

        int idx = position == -1 ? digits.size() - 1 : position;
        if (idx >= digits.size())
            throw new IllegalArgumentException("Position " + (idx + 1) + " out of range, only " + digits.size() + " digits");

        return digits.get(idx);
    }

    // ─────────────────────────────────────────────────────────────
    // Math expression solver
    // ─────────────────────────────────────────────────────────────

    /**
     * Equivalent to Python's solve_math_captcha().
     *
     * Example: "2 * 4 = ?" → 8
     */
    public int solveMath(String expression) {
        if (expression == null)
            throw new IllegalArgumentException("Expression is null");

        // Remove "= ?" and extra whitespace
        String clean = expression.replace("=", "").replace("?", "").strip();

        Pattern p = Pattern.compile("(\\d+)\\s*([+\\-*/×÷xX])\\s*(\\d+)");
        Matcher m = p.matcher(clean);

        if (!m.find())
            throw new IllegalArgumentException("Cannot parse math expression: " + expression);

        int a  = Integer.parseInt(m.group(1));
        char op = m.group(2).charAt(0);
        int b  = Integer.parseInt(m.group(3));

        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*', '×', 'x', 'X' -> a * b;
            case '/', '÷' -> a / b;
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Fallback
    // ─────────────────────────────────────────────────────────────

    private SolveResult tryBoth(String instruction, String expression) {
        try {
            return new SolveResult(solveMath(expression), CaptchaType.MATH_EXPRESSION);
        } catch (Exception ignored) {}
        try {
            return new SolveResult(solvePosition(instruction, expression), CaptchaType.POSITION_BASED);
        } catch (Exception ignored) {}
        throw new IllegalStateException(
                "Cannot solve CAPTCHA. Instruction: '" + instruction + "', Expression: '" + expression + "'");
    }
}
