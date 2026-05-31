package com.example.support.escalation;

import com.example.support.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decides whether the aggregated subagent results are good enough to send to the user,
 * or whether the case should be escalated to a human agent.
 *
 * Three escalation signals (any one can trigger escalation):
 * ─────────────────────────────────────────────────────────
 * 1. CONFIDENCE: min(subagent confidences) < 0.75
 *    → at least one subagent was not sure enough
 *
 * 2. SENTIMENT: inquiry contains high-frustration language
 *    → emotionally distressed customers benefit from human empathy
 *
 * 3. COMPLEXITY: 2+ distinct issue types found across subagents
 *    → complex multi-issue cases need holistic human judgment
 */
public class EscalationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EscalationEvaluator.class);

    private static final double CONFIDENCE_THRESHOLD = 0.75;
    private static final int    COMPLEXITY_THRESHOLD = 2;

    // High-frustration signal words — lightweight, no NLP dependency needed for an exam project
    private static final List<String> HIGH_FRUSTRATION_SIGNALS = List.of(
            "furious", "unacceptable", "terrible", "lawsuit", "awful",
            "worst", "scam", "fraud", "disgusting", "never again"
    );

    public EscalationDecision evaluate(List<SubAgentResult> results, CustomerInquiry inquiry) {
        double minConfidence = results.stream()
                .mapToDouble(SubAgentResult::confidence)
                .min()
                .orElse(0.0);

        boolean highFrustration = detectHighFrustration(inquiry.text());

        long issueTypeCount = results.stream()
                .filter(r -> r.issuesDetected() > 0)
                .count();

        log.info("Escalation eval — minConfidence={:.2f} highFrustration={} issueTypes={}",
                minConfidence, highFrustration, issueTypeCount);

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

        // All signals green — synthesize and resolve
        String synthesized = synthesize(results, inquiry);
        log.info("RESOLVED automatically — response length={}", synthesized.length());
        return EscalationDecision.resolve(synthesized);
    }

    private String synthesize(List<SubAgentResult> results, CustomerInquiry inquiry) {
        // In a production system you'd make one more Claude call here to produce
        // a unified, polished response. For the exam project we concatenate.
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
            sb.append(String.format("low confidence (%.2f < %.2f); ", confidence, CONFIDENCE_THRESHOLD));
        if (frustration)
            sb.append("high customer frustration detected; ");
        if (issueCount >= COMPLEXITY_THRESHOLD)
            sb.append(String.format("complex multi-issue case (%d issue types); ", issueCount));
        return sb.toString().trim();
    }

    private HandoffContext.Priority computePriority(double confidence, boolean highFrustration) {
        if (highFrustration)            return HandoffContext.Priority.HIGH;
        if (confidence < 0.4)          return HandoffContext.Priority.HIGH;
        if (confidence < 0.6)          return HandoffContext.Priority.MEDIUM;
        return HandoffContext.Priority.LOW;
    }
}
