package com.example.support.model;

import java.util.List;

/**
 * All information a human agent needs to continue handling an escalated case.
 *
 * <p><b>EXAM CONCEPT — Escalation / Handoff Pattern:</b><br>
 * When the AI pipeline cannot resolve a case with sufficient confidence, control
 * is transferred to a human agent. The {@code HandoffContext} bundles everything
 * collected during the parallel multi-agent run — no information is lost in the
 * handoff. This mirrors real production escalation pipelines where the AI prepares
 * a structured briefing for the human who picks up the case.
 *
 * @param inquiry               the original customer inquiry
 * @param subAgentSummaries     results from every subagent that ran
 * @param recommendedNextSteps  human-readable explanation of escalation triggers
 *                              and what the human agent should focus on
 * @param priority              urgency level — used to route to the appropriate queue
 */
public record HandoffContext(
        CustomerInquiry inquiry,
        List<SubAgentResult> subAgentSummaries,
        String recommendedNextSteps,
        Priority priority
) {

    /**
     * Ticket urgency level used to route the case to the correct human queue.
     *
     * <ul>
     *   <li>{@code HIGH} — frustrated customer or very low AI confidence → immediate attention</li>
     *   <li>{@code MEDIUM} — moderate confidence gap → handle within SLA</li>
     *   <li>{@code LOW} — borderline confidence → handle when queue allows</li>
     * </ul>
     */
    public enum Priority {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * Static factory for concise construction at the call site.
     *
     * @param inquiry               original customer inquiry
     * @param results               subagent result list
     * @param recommendedNextSteps  escalation reason and guidance
     * @param priority              urgency level
     * @return a fully populated {@code HandoffContext}
     */
    public static HandoffContext of(
            CustomerInquiry inquiry,
            List<SubAgentResult> results,
            String recommendedNextSteps,
            Priority priority
    ) {
        return new HandoffContext(inquiry, results, recommendedNextSteps, priority);
    }
}
