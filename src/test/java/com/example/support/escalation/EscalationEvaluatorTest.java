package com.example.support.escalation;

import com.example.support.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EscalationEvaluator.
 *
 * EXAM CONCEPT — These tests document the three escalation signals:
 *   Signal 1: low confidence   (min < 0.75)
 *   Signal 2: high frustration (keyword match)
 *   Signal 3: high complexity  (≥ 2 agents with issues)
 */
class EscalationEvaluatorTest {

    private EscalationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new EscalationEvaluator();
    }

    // ── Signal 1: Confidence ───────────────────────────────────────────────────

    @Test
    void resolvesWhenAllAgentsHighConfidence() {
        List<SubAgentResult> results = List.of(
                result(AgentRole.FAQ, 0.88, 0)
        );
        EscalationDecision decision = evaluator.evaluate(results, inquiry("What is the return policy?"));

        assertInstanceOf(EscalationDecision.Resolved.class, decision);
        String response = ((EscalationDecision.Resolved) decision).response();
        assertTrue(response.contains("Thank you for reaching out!"));
    }

    @Test
    void escalatesWhenMinConfidenceBelowThreshold() {
        List<SubAgentResult> results = List.of(
                result(AgentRole.FAQ,   0.88, 0),
                result(AgentRole.ORDER, 0.60, 0)   // ← below 0.75
        );
        EscalationDecision decision = evaluator.evaluate(results, inquiry("Normal question"));

        assertEscalated(decision);
    }

    @Test
    void exactlyAtThresholdDoesNotEscalate() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.75, 0));
        EscalationDecision decision = evaluator.evaluate(results, inquiry("Normal question"));

        // 0.75 is NOT below the threshold (strictly <) — should resolve
        assertInstanceOf(EscalationDecision.Resolved.class, decision);
    }

    @Test
    void lowConfidenceFactoryResultAlwaysEscalates() {
        List<SubAgentResult> results = List.of(
                SubAgentResult.lowConfidence(AgentRole.ORDER, "max tool rounds exceeded")
        );
        EscalationDecision decision = evaluator.evaluate(results, inquiry("Normal question"));

        assertEscalated(decision);
    }

    @Test
    void emptyResultListEscalates() {
        // orElse(0.0) when stream is empty → confidence 0.0 < 0.75 → escalate
        EscalationDecision decision = evaluator.evaluate(List.of(), inquiry("Any question"));
        assertEscalated(decision);
    }

    // ── Signal 2: Frustration ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "This is absolutely unacceptable!",
            "Your service is TERRIBLE",
            "I will file a lawsuit",
            "This is the WORST experience",
            "This is a scam",
            "Absolutely disgusting service",
            "I will never again use this store",
            "This is fraud",
            "I am furious",
            "This is awful"
    })
    void escalatesOnFrustrationKeywords(String text) {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.88, 0));
        EscalationDecision decision = evaluator.evaluate(results, inquiry(text));

        assertEscalated(decision,
                "Expected escalation for frustration text: \"" + text + "\"");
    }

    @Test
    void frustrationDetectionIsCaseInsensitive() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.88, 0));

        // Mixed case — should still detect
        assertEscalated(evaluator.evaluate(results, inquiry("UNACCEPTABLE service")));
        assertEscalated(evaluator.evaluate(results, inquiry("Unacceptable service")));
    }

    @Test
    void normalTextDoesNotTriggerFrustration() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.88, 0));
        EscalationDecision decision = evaluator.evaluate(results, inquiry("I would like to return my item"));

        assertInstanceOf(EscalationDecision.Resolved.class, decision);
    }

    // ── Signal 3: Complexity ───────────────────────────────────────────────────

    @Test
    void escalatesWhenTwoOrMoreAgentsDetectIssues() {
        List<SubAgentResult> results = List.of(
                result(AgentRole.ORDER,     0.88, 1),   // ← issue detected
                result(AgentRole.TECHNICAL, 0.88, 1)    // ← issue detected (2 total)
        );
        EscalationDecision decision = evaluator.evaluate(results, inquiry("Normal text"));

        assertEscalated(decision);
    }

    @Test
    void singleAgentWithIssueDoesNotTriggerComplexity() {
        List<SubAgentResult> results = List.of(
                result(AgentRole.ORDER, 0.88, 1)   // only 1 agent with issues
        );
        EscalationDecision decision = evaluator.evaluate(results, inquiry("Normal text"));

        // Only complexity signal would fire — it needs ≥ 2
        assertInstanceOf(EscalationDecision.Resolved.class, decision);
    }

    // ── Priority assignment ────────────────────────────────────────────────────

    @Test
    void frustrationTriggersPriorityHigh() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.88, 0));
        EscalationDecision.Escalated escalated = (EscalationDecision.Escalated)
                evaluator.evaluate(results, inquiry("This is unacceptable!"));

        assertEquals(HandoffContext.Priority.HIGH, escalated.context().priority());
    }

    @Test
    void veryLowConfidenceTriggersPriorityHigh() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.30, 0));
        EscalationDecision.Escalated escalated = (EscalationDecision.Escalated)
                evaluator.evaluate(results, inquiry("Normal question"));

        assertEquals(HandoffContext.Priority.HIGH, escalated.context().priority());
    }

    @Test
    void moderatelyLowConfidenceTriggersPriorityMedium() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.55, 0));
        EscalationDecision.Escalated escalated = (EscalationDecision.Escalated)
                evaluator.evaluate(results, inquiry("Normal question"));

        assertEquals(HandoffContext.Priority.MEDIUM, escalated.context().priority());
    }

    @Test
    void slightlyBelowThresholdTriggersPriorityLow() {
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.70, 0));
        EscalationDecision.Escalated escalated = (EscalationDecision.Escalated)
                evaluator.evaluate(results, inquiry("Normal question"));

        assertEquals(HandoffContext.Priority.LOW, escalated.context().priority());
    }

    // ── HandoffContext content ─────────────────────────────────────────────────

    @Test
    void escalatedContextContainsOriginalInquiryAndResults() {
        CustomerInquiry inquiry = inquiry("This is unacceptable!");
        List<SubAgentResult> results = List.of(result(AgentRole.FAQ, 0.88, 0));

        EscalationDecision.Escalated escalated = (EscalationDecision.Escalated)
                evaluator.evaluate(results, inquiry);

        HandoffContext ctx = escalated.context();
        assertSame(inquiry, ctx.inquiry());
        assertEquals(results, ctx.subAgentSummaries());
        assertFalse(ctx.recommendedNextSteps().isBlank());
    }

    @Test
    void resolvedResponseContainsAllSubagentAnswers() {
        List<SubAgentResult> results = List.of(
                result(AgentRole.FAQ,   0.88, 0, "FAQ answer here"),
                result(AgentRole.ORDER, 0.88, 0, "Order answer here")
        );
        EscalationDecision.Resolved resolved = (EscalationDecision.Resolved)
                evaluator.evaluate(results, inquiry("Normal question"));

        assertTrue(resolved.response().contains("FAQ answer here"));
        assertTrue(resolved.response().contains("Order answer here"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CustomerInquiry inquiry(String text) {
        return new CustomerInquiry("CUST-TEST", null, text, CustomerInquiry.InquiryType.UNKNOWN);
    }

    private SubAgentResult result(AgentRole role, double confidence, int issues) {
        return new SubAgentResult(role, role + " answer", confidence, issues);
    }

    private SubAgentResult result(AgentRole role, double confidence, int issues, String answer) {
        return new SubAgentResult(role, answer, confidence, issues);
    }

    private void assertEscalated(EscalationDecision decision) {
        assertInstanceOf(EscalationDecision.Escalated.class, decision,
                "Expected escalation but got: " + decision.getClass().getSimpleName());
    }

    private void assertEscalated(EscalationDecision decision, String message) {
        assertInstanceOf(EscalationDecision.Escalated.class, decision, message);
    }
}
