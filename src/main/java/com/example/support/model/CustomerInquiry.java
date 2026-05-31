package com.example.support.model;

/**
 * Immutable value object representing a single customer support request.
 *
 * <p><b>EXAM CONCEPT — Immutable domain objects:</b><br>
 * Java records are immutable by default. Using records for data that flows through
 * a multi-agent pipeline prevents accidental mutation across concurrent threads —
 * all three subagents receive the same {@code CustomerInquiry} reference safely.
 *
 * @param customerId unique customer identifier (e.g. {@code CUST-001})
 * @param orderId    nullable — present only for order-related inquiries
 * @param text       the raw customer message text
 * @param type       pre-classified inquiry type; use {@code UNKNOWN} to let the
 *                   coordinator route based on keyword detection
 */
public record CustomerInquiry(
        String customerId,
        String orderId,
        String text,
        InquiryType type
) {

    /**
     * Inquiry classification used by the coordinator for agent routing.
     *
     * <p>{@code UNKNOWN} triggers all-agent fan-out as a safe default —
     * better to involve an extra agent than to miss the correct one.
     */
    public enum InquiryType {
        /** General product question, policy, or how-to. */
        FAQ,
        /** Order status, tracking, or refund request. */
        ORDER,
        /** App crash, login failure, or connectivity issue. */
        TECHNICAL,
        /** Classification not yet determined — coordinator will route broadly. */
        UNKNOWN
    }
}
