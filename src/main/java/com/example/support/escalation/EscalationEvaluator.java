package com.example.support.escalation;

import com.example.support.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decides whether the aggregated subagent results are good enough to resolve automatically,
 * or whether the case must be escalated to a human agent.
 *
 * <hr>
 * <b>EXAM CONCEPT — Escalation / Handoff Pattern</b>
 * <p>
 * After the multi-agent fan-in, this evaluator checks three independent signals.
 * Any single signal is sufficient to trigger escalation — the logic is OR, not AND.
 * This is intentionally conservative: it is better to over-escalate than to send
 * a low-quality or emotionally inappropriate response to an unhappy customer.
 *
 * <p>The three escalation signals are:
 * <ol>
 *   <li><b>CONFIDENCE</b> — {@code min(subagent confidences) < 0.75}<br>
 *       At least one agent was not sure of its answer. Rather than forwarding a
 *       potentially incorrect response, we hand off to a human who can verify.</li>
 *   <li><b>SENTIMENT</b> — inquiry contains high-frustration language<br>
 *       Emotionally distressed customers benefit from human empathy. An AI response,
 *       however technically correct, may feel dismissive in this context.</li>
 *   <li><b>COMPLEXITY</b> — 2+ distinct issue types detected across agents<br>
 *       Multi-issue cases (e.g., wrong order AND app crash) require holistic judgment
 *       that a single-domain agent cannot provide.</li>
 * </ol>
 */
public class EscalationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EscalationEvaluator.class);

    private static final double CONFIDENCE_THRESHOLD = 0.75;
    private static final int    COMPLEXITY_THRESHOLD = 2;

    /**
     * High-frustration signal words.
     *
     * <p>This lightweight keyword list covers the most common strong negative expressions.
     * In production, you would replace this with a sentiment analysis model or a dedicated
     * Claude call that returns a structured frustration score.
     */
    private static final List<String> HIGH_FRUSTRATION_SIGNALS = List.of(
            "furious", "unacceptable", "terrible", "lawsuit", "awful",
            "worst", "scam", "fraud", "disgusting", "never again"
    );

    /**
     * Evaluates all subagent results and the original inquiry to produce a final decision.
     *
     * @param results list of results from all subagents that ran in parallel
     * @param inquiry the original customer inquiry
     * @return {@link EscalationDecision.Resolved} or {@link EscalationDecision.Escalated}
     */
    public EscalationDecision evaluate(List<SubAgentResult> results, CustomerInquiry inquiry) {
        double minConfidence = results.stream()
                .mapToDouble(SubAgentResult::confidence)
                .min()
                .orElse(0.0);

        boolean highFrustration = detectHighFrustration(inquiry.text());

        long issueTypeCount = results.stream()
                .filter(r -> r.issuesDetected() > 0)
                .count();

        log.info("Escalation eval — minConfidence={} highFrustration={} issueTypes={}",
                String.format("%.2f", minConfidence), highFrustration, issueTypeCount);

        boolean shouldEscalate =
                minConfidence < CONFIDENCE_THRESHOLD ||
                highFrustration                      ||
                issueTypeCount >= COMPLEXITY_THRESHOLD;

        if (shouldEscalate) {
            String reason = buildEscalationReason(minConfidence, highFrustration, issueTypeCount);
            log.warn("ESCALATING to human agent — reason: {}", reason);

            HandoffContext context = HandoffContext.of(
                    inquiry,
                    results,
                    "Review all subagent findings. " + reason,
                    computePriority(minConfidence, highFrustration)
            );
            return EscalationDecision.escalate(context);
        }

        // All signals green — synthesize a final response and resolve
        String synthesized = synthesize(results);
        log.info("RESOLVED automatically — response length={}", synthesized.length());
        return EscalationDecision.resolve(synthesized);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Combines subagent answers into a single customer-facing response.
     *
     * <p>In production you would make one final Claude API call here to produce
     * a unified, polished response from the individual subagent answers. For this
     * demo we concatenate — the goal is to illustrate the pattern, not the prose.
     */
    private String synthesize(List<SubAgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thank you for reaching out! Here's what I found:\n\n");
        for (SubAgentResult r : results) {
            sb.append(r.answer()).append("\n\n");
        }
        sb.append("Is there anything else I can help you with?");
        return sb.toString();
    }

    private boolean detectHighFrustration(String text) {
        String lower = text.toLowerCase();
        return HIGH_FRUSTRATION_SIGNALS.stream().anyMatch(lower::contains);
    }

    private String buildEscalationReason(double confidence, boolean frustration, long issueCount) {
        StringBuilder sb = new StringBuilder("Escalation triggers: ");
        if (confidence < CONFIDENCE_THRESHOLD)
            sb.append(String.format("low confidence (%.2f < %.2f); ",
                    confidence, CONFIDENCE_THRESHOLD));
        if (frustration)
            sb.append("high customer frustration detected; ");
        if (issueCount >= COMPLEXITY_THRESHOLD)
            sb.append(String.format("complex multi-issue case (%d issue types); ", issueCount));
        return sb.toString().trim();
    }

    private HandoffContext.Priority computePriority(double confidence, boolean highFrustration) {
        if (highFrustration)   return HandoffContext.Priority.HIGH;
        if (confidence < 0.4)  return HandoffContext.Priority.HIGH;
        if (confidence < 0.6)  return HandoffContext.Priority.MEDIUM;
        return HandoffContext.Priority.LOW;
    }
}
