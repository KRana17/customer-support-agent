package com.example.support.mcp;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.example.support.model.AgentRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Builds the {@link Tool} list for each subagent role.
 *
 * <p><b>EXAM CONCEPT — Role-Scoped Tool Access:</b><br>
 * Tools are intentionally scoped per role, not shared globally. A FAQ agent has
 * no access to {@code initiate_refund}. This prevents Claude from hallucinating
 * tool calls outside its responsibility domain — security through least-privilege.
 *
 * <p><b>EXAM CONCEPT — Structured JSON Schema for Tools:</b><br>
 * Each tool's {@code inputSchema} is a JSON Schema object that tells Claude exactly
 * what parameters to supply when calling the tool.
 *
 * <p>The Anthropic Java SDK uses a typed {@link Tool.InputSchema} builder:
 * <ul>
 *   <li>{@code .type(JsonValue.from("object"))} — sets the schema type</li>
 *   <li>{@code .properties(Tool.InputSchema.Properties)} — the field definitions,
 *       built via {@link Tool.InputSchema.Properties#builder()} with one
 *       {@code putAdditionalProperty(name, JsonValue)} call per field</li>
 *   <li>{@code .required(List.of("field_name"))} — required field names</li>
 * </ul>
 *
 * <p>Each property value is a JSON Schema field object like:
 * <pre>{@code
 * {"type": "string", "description": "The order ID to look up"}
 * }</pre>
 * which is built as a Jackson {@link ObjectNode} and wrapped via
 * {@link JsonValue#fromJsonNode(com.fasterxml.jackson.databind.JsonNode)}.
 */
public class MCPToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns the tools appropriate for the given agent role.
     *
     * @param role the subagent role requesting its tool list
     * @return an unmodifiable list of tools Claude may call during its tool-use loop
     */
    public List<Tool> buildToolsFor(AgentRole role) {
        return switch (role) {
            case ORDER     -> List.of(lookupOrderTool(), initiateRefundTool());
            case FAQ       -> List.of(searchKnowledgeBaseTool());
            case TECHNICAL -> List.of(runDiagnosticTool(), createTicketTool());
        };
    }

    // ── Tool definitions ───────────────────────────────────────────────────────

    private Tool lookupOrderTool() {
        return Tool.builder()
                .name("lookup_order")
                .description("""
                        Retrieves order status, tracking info, line items, and refund eligibility
                        for a given order ID. Call this FIRST before making any order-related statement.
                        Do not guess order status — always look it up.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(singleFieldProperties(
                                "order_id", "string",
                                "The order ID to look up (e.g. ORD-77291)"))
                        .required(List.of("order_id"))
                        .build())
                .build();
    }

    private Tool initiateRefundTool() {
        return Tool.builder()
                .name("initiate_refund")
                .description("""
                        Initiates a full refund for an order. Safety rules (enforce both):
                        1. The customer must have EXPLICITLY requested a refund in their message.
                        2. lookup_order must confirm refund_eligible=true for the order.
                        Never speculatively initiate refunds. Never refund ineligible orders.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(singleFieldProperties(
                                "order_id", "string",
                                "The order ID to refund"))
                        .required(List.of("order_id"))
                        .build())
                .build();
    }

    private Tool searchKnowledgeBaseTool() {
        return Tool.builder()
                .name("search_knowledge_base")
                .description("""
                        Semantic search over product FAQ, return policy, shipping info, and help docs.
                        Always search before answering any general question — never guess or fabricate
                        policy details. Use the customer's exact question as the query for best results.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(singleFieldProperties(
                                "query", "string",
                                "The customer's question verbatim or a close paraphrase"))
                        .required(List.of("query"))
                        .build())
                .build();
    }

    private Tool runDiagnosticTool() {
        return Tool.builder()
                .name("run_diagnostic")
                .description("""
                        Runs account and connectivity diagnostics: checks account status, last login,
                        known platform incidents, and connectivity test results for the user.
                        Run this BEFORE advising on any technical issue — do not guess the root cause.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(singleFieldProperties(
                                "user_id", "string",
                                "The customer's account ID (same as customerId in the inquiry)"))
                        .required(List.of("user_id"))
                        .build())
                .build();
    }

    private Tool createTicketTool() {
        return Tool.builder()
                .name("create_ticket")
                .description("""
                        Creates a support ticket for issues that cannot be resolved automatically.
                        Use after running diagnostics. Include a clear, actionable summary.
                        Priority values: LOW | MEDIUM | HIGH | CRITICAL
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(buildTicketProperties())
                        .required(List.of("summary", "priority"))
                        .build())
                .build();
    }

    // ── Schema helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a {@link Tool.InputSchema.Properties} with a single named field.
     *
     * <p>The resulting JSON Schema structure for the property is:
     * <pre>{@code
     * {
     *   "<name>": { "type": "<type>", "description": "<description>" }
     * }
     * }</pre>
     *
     * <p><b>SDK Note:</b> {@link Tool.InputSchema.Properties} uses the additional
     * properties map as its storage — each key is a field name, each value is a
     * {@link JsonValue} containing the field's JSON Schema object.
     */
    private Tool.InputSchema.Properties singleFieldProperties(
            String name, String type, String description) {
        ObjectNode fieldSchema = mapper.createObjectNode();
        fieldSchema.put("type", type);
        fieldSchema.put("description", description);

        return Tool.InputSchema.Properties.builder()
                .putAdditionalProperty(name, JsonValue.fromJsonNode(fieldSchema))
                .build();
    }

    /**
     * Builds the two-field properties schema for the {@code create_ticket} tool.
     */
    private Tool.InputSchema.Properties buildTicketProperties() {
        ObjectNode summarySchema = mapper.createObjectNode();
        summarySchema.put("type", "string");
        summarySchema.put("description",
                "Brief description of the issue — enough for a tier-2 agent to understand " +
                "without reading the full conversation");

        ObjectNode prioritySchema = mapper.createObjectNode();
        prioritySchema.put("type", "string");
        prioritySchema.put("description", "Ticket priority: LOW, MEDIUM, HIGH, or CRITICAL");

        return Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("summary",  JsonValue.fromJsonNode(summarySchema))
                .putAdditionalProperty("priority", JsonValue.fromJsonNode(prioritySchema))
                .build();
    }
}
