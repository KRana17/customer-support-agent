package com.example.support.mcp;

import com.anthropic.models.messages.Tool;
import com.anthropic.models.shared.FunctionDefinition;
import com.example.support.model.AgentRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Builds the Tool list for each subagent role.
 *
 * KEY DESIGN DECISION: Tools are scoped per role — not shared globally.
 * A FAQ agent has no access to initiate_refund. This prevents Claude from
 * hallucinating tool calls outside its responsibility domain.
 *
 * The inputSchema for each tool is a JSON Schema object describing the
 * parameters Claude must supply when it calls the tool.
 */
public class MCPToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();

    public List<Tool> buildToolsFor(AgentRole role) {
        return switch (role) {
            case ORDER    -> List.of(lookupOrderTool(), initiateRefundTool());
            case FAQ      -> List.of(searchKnowledgeBaseTool());
            case TECHNICAL -> List.of(runDiagnosticTool(), createTicketTool());
        };
    }

    private Tool lookupOrderTool() {
        return Tool.builder()
                .name("lookup_order")
                .description("""
                    Retrieves order status, tracking info, line items, and refund eligibility
                    for a given order ID. Call this first before any order-related response.
                    """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .putAdditionalProperty("properties", propertiesNode(
                                "order_id", "string", "The order ID to look up"
                        ))
                        .putAdditionalProperty("required", mapper.createArrayNode().add("order_id"))
                        .build())
                .build();
    }

    private Tool initiateRefundTool() {
        return Tool.builder()
                .name("initiate_refund")
                .description("""
                    Initiates a refund for a delivered order. Only call this if the customer
                    explicitly requests a refund AND lookup_order confirms refund_eligible=true.
                    Never speculatively initiate refunds.
                    """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .putAdditionalProperty("properties", propertiesNode(
                                "order_id", "string", "The order ID to refund"
                        ))
                        .putAdditionalProperty("required", mapper.createArrayNode().add("order_id"))
                        .build())
                .build();
    }

    private Tool searchKnowledgeBaseTool() {
        return Tool.builder()
                .name("search_knowledge_base")
                .description("""
                    Semantic search over product FAQ, return policy, shipping info, and help docs.
                    Use this before answering any general question. Include the user's exact
                    question as the query for best results.
                    """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .putAdditionalProperty("properties", propertiesNode(
                                "query", "string", "The user's question verbatim"
                        ))
                        .putAdditionalProperty("required", mapper.createArrayNode().add("query"))
                        .build())
                .build();
    }

    private Tool runDiagnosticTool() {
        return Tool.builder()
                .name("run_diagnostic")
                .description("""
                    Runs account and connectivity diagnostics for a user: checks account status,
                    last login, any known platform incidents, and connectivity test results.
                    Run this before advising on any technical issue.
                    """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .putAdditionalProperty("properties", propertiesNode(
                                "user_id", "string", "The user's account ID"
                        ))
                        .putAdditionalProperty("required", mapper.createArrayNode().add("user_id"))
                        .build())
                .build();
    }

    private Tool createTicketTool() {
        return Tool.builder()
                .name("create_ticket")
                .description("""
                    Creates a support ticket in the ticketing system for issues that cannot be
                    resolved automatically. Include a clear summary and accurate priority.
                    Priority: LOW | MEDIUM | HIGH | CRITICAL
                    """)
                .inputSchema(Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .putAdditionalProperty("properties", buildTicketProperties())
                        .putAdditionalProperty("required",
                                mapper.createArrayNode().add("summary").add("priority"))
                        .build())
                .build();
    }

    /** Helper to build a simple single-property schema node. */
    private ObjectNode propertiesNode(String name, String type, String description) {
        ObjectNode props = mapper.createObjectNode();
        ObjectNode field = mapper.createObjectNode();
        field.put("type", type);
        field.put("description", description);
        props.set(name, field);
        return props;
    }

    private ObjectNode buildTicketProperties() {
        ObjectNode props = mapper.createObjectNode();

        ObjectNode summary = mapper.createObjectNode();
        summary.put("type", "string");
        summary.put("description", "Brief description of the issue");
        props.set("summary", summary);

        ObjectNode priority = mapper.createObjectNode();
        priority.put("type", "string");
        priority.put("description", "Ticket priority: LOW, MEDIUM, HIGH, or CRITICAL");
        props.set("priority", priority);

        return props;
    }
}
