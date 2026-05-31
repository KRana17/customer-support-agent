package com.example.support.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Simulates MCP tool dispatch.
 *
 * REAL USAGE: Replace each case with an actual HTTP call to your MCP server endpoint.
 * The SDK gives you the tool name + a JSON input object; you call the server and return JSON.
 *
 * Tool names here must EXACTLY match the names registered in MCPToolRegistry.
 */
public class MCPToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(MCPToolExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Dispatches a tool call by name and returns a JSON string result.
     *
     * @param toolName  the tool name Claude chose (from the tool_use block)
     * @param inputJson the JSON arguments Claude produced for the tool
     * @return a JSON string that will be sent back as a tool_result block
     */
    public String execute(String toolName, String inputJson) {
        log.debug("MCP dispatch → tool='{}' input={}", toolName, inputJson);

        try {
            JsonNode input = mapper.readTree(inputJson);
            String result = switch (toolName) {
                case "lookup_order"        -> lookupOrder(input);
                case "initiate_refund"     -> initiateRefund(input);
                case "search_knowledge_base" -> searchKnowledgeBase(input);
                case "run_diagnostic"      -> runDiagnostic(input);
                case "create_ticket"       -> createTicket(input);
                default -> errorJson("Unknown tool: " + toolName);
            };
            log.debug("MCP result ← tool='{}' result={}", toolName, result);
            return result;
        } catch (Exception e) {
            log.error("MCP tool execution failed for '{}': {}", toolName, e.getMessage());
            return errorJson("Tool execution failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stub implementations — replace these with real HTTP calls to MCP servers
    // ──────────────────────────────────────────────────────────────────────────

    private String lookupOrder(JsonNode input) throws Exception {
        String orderId = input.path("order_id").asText("unknown");
        // TODO: replace with → GET https://your-orders-mcp.internal/orders/{orderId}
        ObjectNode result = mapper.createObjectNode();
        result.put("order_id", orderId);
        result.put("status", "SHIPPED");
        result.put("tracking_number", "TRK-88291-CA");
        result.put("estimated_delivery", "2026-05-18");
        result.put("total_amount", 149.99);
        result.put("refund_eligible", true);
        return mapper.writeValueAsString(result);
    }

    private String initiateRefund(JsonNode input) throws Exception {
        String orderId = input.path("order_id").asText("unknown");
        // TODO: replace with → POST https://your-orders-mcp.internal/refunds
        ObjectNode result = mapper.createObjectNode();
        result.put("refund_id", "REF-" + System.currentTimeMillis());
        result.put("order_id", orderId);
        result.put("status", "INITIATED");
        result.put("estimated_credit_days", 3);
        return mapper.writeValueAsString(result);
    }

    private String searchKnowledgeBase(JsonNode input) throws Exception {
        String query = input.path("query").asText("");
        // TODO: replace with → POST https://your-docs-mcp.internal/search {"query": query}
        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        result.put("top_result", "Return and refund policy: Items can be returned within 30 days of delivery for a full refund. Digital products are non-refundable.");
        result.put("confidence", 0.92);
        result.put("source_url", "https://help.yourstore.com/returns");
        return mapper.writeValueAsString(result);
    }

    private String runDiagnostic(JsonNode input) throws Exception {
        String userId = input.path("user_id").asText("unknown");
        // TODO: replace with → POST https://your-diagnostics-mcp.internal/run {"user_id": userId}
        ObjectNode result = mapper.createObjectNode();
        result.put("user_id", userId);
        result.put("account_status", "ACTIVE");
        result.put("last_login", "2026-05-15T14:32:00Z");
        result.put("connectivity_test", "PASS");
        result.put("known_incidents", false);
        return mapper.writeValueAsString(result);
    }

    private String createTicket(JsonNode input) throws Exception {
        String summary = input.path("summary").asText("Support request");
        String priority = input.path("priority").asText("MEDIUM");
        // TODO: replace with → POST https://your-ticketing-mcp.internal/tickets
        ObjectNode result = mapper.createObjectNode();
        result.put("ticket_id", "TKT-" + (int)(Math.random() * 90000 + 10000));
        result.put("summary", summary);
        result.put("priority", priority);
        result.put("status", "OPEN");
        result.put("assigned_queue", "TIER_2_SUPPORT");
        return mapper.writeValueAsString(result);
    }

    private String errorJson(String message) {
        try {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", message);
            return mapper.writeValueAsString(err);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
