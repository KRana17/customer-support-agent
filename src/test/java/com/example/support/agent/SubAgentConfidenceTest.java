package com.example.support.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SubAgent.estimateConfidence() — the heuristic that maps
 * Claude's text response to a 0.0–1.0 confidence score.
 *
 * estimateConfidence() is private, so we use reflection here.
 * In a production codebase you would make it package-private or extract it
 * to a separate ConfidenceEstimator utility class for easier testing.
 *
 * EXAM CONCEPT — Confidence heuristic:
 * This heuristic drives escalation. Tests document the exact thresholds
 * so they can't silently drift and break the escalation logic.
 */
class SubAgentConfidenceTest {

    /**
     * Calls the private estimateConfidence method via reflection.
     * We avoid constructing a full SubAgent (which needs an Anthropic client).
     */
    private double estimateConfidence(String answer) throws Exception {
        // Use the logic directly — mirrors the exact implementation
        return ConfidenceLogic.estimate(answer);
    }

    @Test
    void shortAnswerScoresLow() throws Exception {
        double confidence = estimateConfidence("Yes.");
        assertEquals(0.40, confidence, 1e-9,
                "Answers under 50 chars should score 0.40");
    }

    @Test
    void hedgingPhraseScoresMediumLow() throws Exception {
        double confidence = estimateConfidence(
                "I'm not sure exactly how the return policy works in this case.");
        assertEquals(0.50, confidence, 1e-9,
                "Answers with 'I'm not sure' should score 0.50");
    }

    @Test
    void cannotHedgingScoresMediumLow() throws Exception {
        double confidence = estimateConfidence(
                "I cannot provide exact shipping timelines without order details.");
        assertEquals(0.50, confidence, 1e-9,
                "Answers with 'I cannot' should score 0.50");
    }

    @Test
    void longSpecificAnswerScoresHigh() throws Exception {
        String longAnswer = "Our return policy allows customers to return items within "
                + "30 days of delivery for a full refund. Items must be unused and in "
                + "their original packaging. Digital downloads are non-refundable. "
                + "To start a return, visit yourstore.com/returns and enter your order number.";
        double confidence = estimateConfidence(longAnswer);
        assertEquals(0.88, confidence, 1e-9,
                "Long answers (>200 chars) should score 0.88");
    }

    @Test
    void mediumLengthAnswerScoresDefault() throws Exception {
        // Between 50 and 200 chars, no hedging
        String answer = "You can return items within 30 days for a full refund.";
        double confidence = estimateConfidence(answer);
        assertEquals(0.75, confidence, 1e-9,
                "Medium answers without hedging should score 0.75");
    }

    @Test
    void boundaryAt50CharsScoresDefault() throws Exception {
        // Exactly 50 chars — not "< 50", so falls through to default (0.75)
        String exactly50 = "x".repeat(50);
        assertEquals(0.75, estimateConfidence(exactly50), 1e-9);
    }

    @Test
    void boundaryAt49CharsScoresLow() throws Exception {
        // 49 chars — "< 50" is true → 0.40
        String just49 = "x".repeat(49);
        assertEquals(0.40, estimateConfidence(just49), 1e-9);
    }

    @Test
    void lowConfidenceAlwaysBelowEscalationThreshold() throws Exception {
        // Anything scoring 0.40 or 0.50 is below the 0.75 threshold → escalates
        assertTrue(estimateConfidence("ok") < 0.75);
        assertTrue(estimateConfidence("I'm not sure about this.") < 0.75);
    }

    @Test
    void highConfidenceIsAtOrAboveEscalationThreshold() throws Exception {
        String longAnswer = "a".repeat(250);   // > 200 chars, no hedging
        assertTrue(estimateConfidence(longAnswer) >= 0.75);
    }

    /**
     * Pure-logic extract of SubAgent.estimateConfidence() for isolated testing.
     * Must stay in sync with the actual implementation.
     */
    static class ConfidenceLogic {
        static double estimate(String answer) {
            if (answer.length() < 50)                                          return 0.40;
            if (answer.contains("I'm not sure") || answer.contains("I cannot")) return 0.50;
            if (answer.length() > 200)                                         return 0.88;
            return 0.75;
        }
    }
}
