package com.example.support.model;

/**
 * Defines the three domain-specialized subagent roles.
 *
 * <p><b>EXAM CONCEPT — Role-Scoped Tool Access:</b><br>
 * {@link com.example.support.mcp.MCPToolRegistry} uses this enum to give each agent
 * ONLY the tools appropriate to its domain:
 * <ul>
 *   <li>{@code FAQ} → {@code search_knowledge_base}</li>
 *   <li>{@code ORDER} → {@code lookup_order}, {@code initiate_refund}</li>
 *   <li>{@code TECHNICAL} → {@code run_diagnostic}, {@code create_ticket}</li>
 * </ul>
 *
 * <p>This is a key safety pattern: a FAQ agent cannot accidentally call
 * {@code initiate_refund}, because that tool is never included in its tool list.
 * Claude can only call tools it has been given — scoping at the registry level
 * enforces business rules without relying on prompt instructions alone.
 */
public enum AgentRole {

    /** Handles general product questions, return policy, and shipping FAQ. */
    FAQ,

    /** Handles order lookup, shipment tracking, and refund initiation. */
    ORDER,

    /** Handles account diagnostics and support ticket creation. */
    TECHNICAL
}
