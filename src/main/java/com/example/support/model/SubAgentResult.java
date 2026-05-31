package com.example.support.model;

/**
 * The output produced by a single {@link com.example.support.agent.SubAgent}
 * after completing its tool-use loop.
 *
 * <p><b>EXAM CONCEPT — Fan-in result aggregation:</b><br>
 * The coordinator collects a {@code SubAgentResult} from every subagent after the
 * parallel fan-out completes. The {@code confidence} score is the primary input to
 * {@link com.example.support.escalation.EscalationEvaluator}: if any agent returns
 * confidence below 0.75, the whole case is escalated to a human agent.
 *
 * @param agentRole       which agent produced this result
 * @param answer          the natural-language response Claude generated
 * @param confidence      estimated answer quality from 0.0 (no idea) to 1.0 (certain)
 * @param issuesDetected  count of distinct problems identified (used for complexity signal)
 */
public record SubAgentResult(
        AgentRole agentRole,
        String answer,
        double confidence,
        int issuesDetected
) {

    /**
     * Factory for low-confidence fallback results.
     *
     * <p>Used defensively when a subagent:
     * <ul>
     *   <li>hits {@code MAX_TOOL_ROUNDS} without resolving</li>
     *   <li>receives an unexpected {@code stop_reason} from the API</li>
     *   <li>encounters an unhandled exception during tool execution</li>
     * </ul>
     *
     * <p>A confidence of {@code 0.3} is well below the {@code 0.75} threshold,
     * guaranteeing escalation to a human agent rather than sending a bad response.
     *
     * @param role   the agent role that failed
     * @param reason human-readable description of why the agent could not resolve
     * @return a result that will trigger escalation in {@code EscalationEvaluator}
     */
    public static SubAgentResult lowConfidence(AgentRole role, String reason) {
        return new SubAgentResult(
                role,
                "Agent was unable to resolve: " + reason,
                0.3,
                1
        );
    }
}
