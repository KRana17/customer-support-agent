package com.example.support.model;

/**
 * Sealed interface representing the two terminal outcomes of the multi-agent pipeline.
 *
 * <p><b>EXAM CONCEPT — Sealed Interfaces + Pattern Matching:</b><br>
 * Sealed interfaces (Java 17+) model discriminated unions. Combined with
 * Java 21 pattern-matching {@code switch}, the caller is forced by the compiler
 * to handle ALL variants — no unchecked casts, no missed cases:
 *
 * <pre>{@code
 * switch (decision) {
 *     case EscalationDecision.Resolved resolved ->
 *         System.out.println(resolved.response());
 *     case EscalationDecision.Escalated escalated ->
 *         System.out.println(escalated.context().priority());
 * }
 * }</pre>
 *
 * <p><b>EXAM CONCEPT — Escalation / Handoff Pattern:</b><br>
 * The pipeline always produces exactly one of these two outcomes. If resolved,
 * the response goes directly to the customer. If escalated, the full
 * {@link HandoffContext} (containing all subagent findings) is handed to a human.
 */
public sealed interface EscalationDecision
        permits EscalationDecision.Resolved, EscalationDecision.Escalated {

    /**
     * The agent pipeline produced a high-confidence answer suitable for the customer.
     *
     * @param response the final natural-language response to send to the customer
     */
    record Resolved(String response) implements EscalationDecision {}

    /**
     * The agent pipeline could not resolve the case with sufficient confidence.
     * Full context is attached for the human agent who picks up the case.
     *
     * @param context all subagent findings, priority level, and recommended next steps
     */
    record Escalated(HandoffContext context) implements EscalationDecision {}

    /**
     * Factory — wraps a response string into a {@link Resolved} outcome.
     *
     * @param response the customer-facing answer
     * @return a resolved decision
     */
    static EscalationDecision resolve(String response) {
        return new Resolved(response);
    }

    /**
     * Factory — wraps a {@link HandoffContext} into an {@link Escalated} outcome.
     *
     * @param context the full handoff context for the human agent
     * @return an escalated decision
     */
    static EscalationDecision escalate(HandoffContext context) {
        return new Escalated(context);
    }
}
