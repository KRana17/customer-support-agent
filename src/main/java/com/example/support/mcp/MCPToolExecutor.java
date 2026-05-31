package com.example.support.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Simulates MCP (Model Context Protocol) tool dispatch backed by an Excel data file.
 *
 * <hr>
 * <b>EXAM CONCEPT — Tool Execution Layer</b>
 * <p>
 * In a real production system, each {@code case} in {@link #execute} would make an
 * HTTP call to a dedicated MCP server (e.g., an orders service, a knowledge base
 * service). The interface — tool name + JSON input → JSON output — is identical
 * regardless of whether the backend is a real server or a file.
 *
 * <p>This demo uses an Excel workbook ({@code mock-data.xlsx}) so you can:
 * <ul>
 *   <li>Inspect and modify the test data without touching Java code</li>
 *   <li>Add new order, KB, or diagnostic rows to explore different agent behaviors</li>
 *   <li>Understand the data contract each tool expects and returns</li>
 * </ul>
 *
 * <hr>
 * <b>EXAM CONCEPT — Tool Input/Output Contract</b>
 * <p>
 * Claude sends tool calls as JSON objects. The structure matches the
 * {@code inputSchema} defined in {@link MCPToolRegistry}. This class parses
 * that JSON, looks up the relevant data, and returns a JSON result that Claude
 * incorporates into its next reasoning step.
 *
 * <hr>
 * <b>Replacing stubs with real MCP servers (3 steps):</b>
 * <ol>
 *   <li>Remove the Excel loading code and the in-memory maps.</li>
 *   <li>Inject an HTTP client (e.g., OkHttp or Spring WebClient).</li>
 *   <li>Replace each {@code private String lookupOrder(JsonNode)} implementation
 *       with {@code return httpClient.post("https://orders-mcp.internal/lookup", inputJson)}.</li>
 * </ol>
 * The {@link #execute} dispatcher and all callers remain unchanged.
 */
public class MCPToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(MCPToolExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /*
     * In-memory maps loaded from mock-data.xlsx at construction time.
     * Maps are populated once and never mutated — safe for concurrent read
     * access from multiple virtual threads running in parallel subagents.
     */
    private final Map<String, ObjectNode> ordersById          = new HashMap<>();
    private final Map<String, ObjectNode> knowledgeBaseByTopic = new HashMap<>();
    private final Map<String, ObjectNode> diagnosticsByUserId  = new HashMap<>();

    /**
     * Loads mock data from {@code mock-data.xlsx} on the classpath.
     *
     * @throws IllegalStateException if the file cannot be found or parsed —
     *         fail fast at startup rather than silently returning empty results
     */
    public MCPToolExecutor() {
        loadExcelData();
    }

    // ── Excel loading ──────────────────────────────────────────────────────────

    private void loadExcelData() {
        InputStream is = getClass().getClassLoader().getResourceAsStream("mock-data.xlsx");
        if (is == null) {
            throw new IllegalStateException(
                    "mock-data.xlsx not found on classpath. " +
                    "Run: mvn compile  (or run MockDataGenerator if the file doesn't exist yet)");
        }
        try (is; Workbook wb = new XSSFWorkbook(is)) {

            loadOrders(wb.getSheet("Orders"));
            loadKnowledgeBase(wb.getSheet("KnowledgeBase"));
            loadDiagnostics(wb.getSheet("Diagnostics"));

            log.info("Mock data loaded — orders={} knowledgeBase={} diagnostics={}",
                    ordersById.size(), knowledgeBaseByTopic.size(), diagnosticsByUserId.size());

        } catch (IllegalStateException e) {
            throw e; // re-throw our own errors unchanged
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load mock-data.xlsx: " + e.getMessage() +
                    ". Ensure the file exists at src/main/resources/mock-data.xlsx " +
                    "and all three sheets (Orders, KnowledgeBase, Diagnostics) are present.", e);
        }
    }

    /**
     * Loads the {@code Orders} sheet.
     *
     * <p>Expected columns (row 0 = header):
     * {@code order_id | customer_id | status | tracking_number | estimated_delivery | total_amount | refund_eligible}
     */
    private void loadOrders(Sheet sheet) {
        if (sheet == null) throw new IllegalStateException("Sheet 'Orders' not found in mock-data.xlsx");
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            ObjectNode node = mapper.createObjectNode();
            String orderId = cell(row, 0);
            if (orderId.isBlank()) continue;
            node.put("order_id",           orderId);
            node.put("customer_id",        cell(row, 1));
            node.put("status",             cell(row, 2));
            node.put("tracking_number",    cell(row, 3));
            node.put("estimated_delivery", cell(row, 4));
            node.put("total_amount",       parseDouble(cell(row, 5)));
            node.put("refund_eligible",    Boolean.parseBoolean(cell(row, 6)));
            ordersById.put(orderId.toUpperCase(), node);
        }
    }

    /**
     * Loads the {@code KnowledgeBase} sheet.
     *
     * <p>Expected columns (row 0 = header):
     * {@code topic | keywords | answer | source_url | confidence}
     */
    private void loadKnowledgeBase(Sheet sheet) {
        if (sheet == null) throw new IllegalStateException("Sheet 'KnowledgeBase' not found in mock-data.xlsx");
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            ObjectNode node = mapper.createObjectNode();
            String topic = cell(row, 0);
            if (topic.isBlank()) continue;
            node.put("topic",      topic);
            node.put("keywords",   cell(row, 1));
            node.put("answer",     cell(row, 2));
            node.put("source_url", cell(row, 3));
            node.put("confidence", parseDouble(cell(row, 4)));
            knowledgeBaseByTopic.put(topic.toLowerCase(), node);
        }
    }

    /**
     * Loads the {@code Diagnostics} sheet.
     *
     * <p>Expected columns (row 0 = header):
     * {@code user_id | account_status | last_login | connectivity_test | known_incidents | incident_description}
     */
    private void loadDiagnostics(Sheet sheet) {
        if (sheet == null) throw new IllegalStateException("Sheet 'Diagnostics' not found in mock-data.xlsx");
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            ObjectNode node = mapper.createObjectNode();
            String userId = cell(row, 0);
            if (userId.isBlank()) continue;
            node.put("user_id",              userId);
            node.put("account_status",       cell(row, 1));
            node.put("last_login",           cell(row, 2));
            node.put("connectivity_test",    cell(row, 3));
            node.put("known_incidents",      Boolean.parseBoolean(cell(row, 4)));
            node.put("incident_description", cell(row, 5));
            diagnosticsByUserId.put(userId.toUpperCase(), node);
        }
    }

    /**
     * Null-safe cell reader that normalizes all cell types to a trimmed string.
     *
     * <p>Apache POI reports numeric cells (including IDs like "77291") as {@code NUMERIC}.
     * This helper converts them cleanly. To avoid the trailing {@code .0} on integer-valued
     * cells, we cast to {@code long} when the value is a whole number.
     */
    private String cell(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case NUMERIC -> {
                double v = c.getNumericCellValue();
                yield (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default      -> c.getStringCellValue().trim();
        };
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // ── Tool dispatch ──────────────────────────────────────────────────────────

    /**
     * Routes a tool call by name and returns a JSON string result.
     *
     * <p>This method is called from {@link com.example.support.agent.SubAgent} for
     * every {@code ToolUseBlock} in the API response. The name and input come directly
     * from Claude — they match the names and schemas registered in {@link MCPToolRegistry}.
     *
     * @param toolName  the tool name Claude chose (from the tool_use block)
     * @param inputJson the JSON arguments Claude produced for the tool
     * @return a JSON string sent back as a {@code tool_result} content block
     */
    public String execute(String toolName, String inputJson) {
        log.debug("MCP dispatch → tool='{}' input={}", toolName, inputJson);
        try {
            JsonNode input = mapper.readTree(inputJson);
            String result = switch (toolName) {
                case "lookup_order"          -> lookupOrder(input);
                case "initiate_refund"       -> initiateRefund(input);
                case "search_knowledge_base" -> searchKnowledgeBase(input);
                case "run_diagnostic"        -> runDiagnostic(input);
                case "create_ticket"         -> createTicket(input);
                default -> errorJson("Unknown tool: " + toolName +
                        ". Check MCPToolRegistry for registered tool names.");
            };
            log.debug("MCP result ← tool='{}' result={}", toolName, result);
            return result;
        } catch (Exception e) {
            log.error("MCP tool execution failed for '{}': {}", toolName, e.getMessage());
            return errorJson("Tool execution failed: " + e.getMessage());
        }
    }

    // ── Tool implementations ───────────────────────────────────────────────────

    private String lookupOrder(JsonNode input) throws Exception {
        String orderId = input.path("order_id").asText("").toUpperCase()
                .replace("ORD-", "ORD-"); // normalize any casing Claude uses
        ObjectNode result = ordersById.getOrDefault(
                orderId,
                notFoundNode("order", orderId,
                        "Tip: Check the Orders sheet in mock-data.xlsx for valid order IDs")
        );
        return mapper.writeValueAsString(result);
    }

    private String initiateRefund(JsonNode input) throws Exception {
        String orderId = input.path("order_id").asText("").toUpperCase();
        ObjectNode order = ordersById.get(orderId);

        if (order == null) {
            return errorJson("Cannot initiate refund — order not found: " + orderId);
        }
        if (!order.path("refund_eligible").asBoolean(false)) {
            return errorJson("Cannot initiate refund — order " + orderId +
                    " has refund_eligible=false (status: " +
                    order.path("status").asText("unknown") + ")");
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("refund_id",             "REF-" + System.currentTimeMillis());
        result.put("order_id",              orderId);
        result.put("status",                "INITIATED");
        result.put("estimated_credit_days", 3);
        result.put("message",               "Refund initiated successfully. Credit will appear in 3 business days.");
        return mapper.writeValueAsString(result);
    }

    private String searchKnowledgeBase(JsonNode input) throws Exception {
        String query = input.path("query").asText("").toLowerCase();

        // Keyword-score matching: count how many comma-separated keywords appear in the query
        ObjectNode best = null;
        int bestScore   = 0;

        for (ObjectNode entry : knowledgeBaseByTopic.values()) {
            String keywords = entry.path("keywords").asText("").toLowerCase();
            int score = 0;
            for (String kw : keywords.split(",")) {
                if (query.contains(kw.trim())) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best      = entry;
            }
        }

        if (best == null || bestScore == 0) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("query",      query);
            fallback.put("top_result", "No matching knowledge base entry found for this query.");
            fallback.put("confidence", 0.30);
            fallback.put("suggestion", "Try rephrasing the query or check KnowledgeBase sheet for available topics.");
            return mapper.writeValueAsString(fallback);
        }

        ObjectNode result = best.deepCopy();
        result.put("query",       query);
        result.put("match_score", bestScore);
        return mapper.writeValueAsString(result);
    }

    private String runDiagnostic(JsonNode input) throws Exception {
        String userId = input.path("user_id").asText("").toUpperCase();
        ObjectNode result = diagnosticsByUserId.getOrDefault(
                userId,
                notFoundNode("user", userId,
                        "Tip: Check the Diagnostics sheet in mock-data.xlsx for valid user IDs")
        );
        return mapper.writeValueAsString(result);
    }

    private String createTicket(JsonNode input) throws Exception {
        String summary  = input.path("summary").asText("Support request");
        String priority = input.path("priority").asText("MEDIUM");

        ObjectNode result = mapper.createObjectNode();
        result.put("ticket_id",      "TKT-" + (int)(Math.random() * 90000 + 10000));
        result.put("summary",        summary);
        result.put("priority",       priority);
        result.put("status",         "OPEN");
        result.put("assigned_queue", "TIER_2_SUPPORT");
        result.put("message",        "Ticket created. A tier-2 agent will follow up within 24 hours.");
        return mapper.writeValueAsString(result);
    }

    // ── Response helpers ───────────────────────────────────────────────────────

    private ObjectNode notFoundNode(String type, String id, String hint) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", type + " not found: " + id);
        node.put("hint", hint);
        return node;
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
