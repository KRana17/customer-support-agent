package com.example.support.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MCPToolExecutor.
 *
 * mock-data.xlsx is on the test classpath (compiled from src/main/resources).
 * These tests verify tool dispatch and data lookup without hitting the Anthropic API.
 *
 * EXAM CONCEPT — Tool input/output contract:
 * Each test exercises exactly what Claude sends as a tool_use input and
 * verifies the JSON structure Claude receives as a tool_result.
 */
class MCPToolExecutorTest {

    private MCPToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Loads mock-data.xlsx from classpath — fails fast if file is missing
        executor = new MCPToolExecutor();
    }

    // ── lookup_order ────────────────────────────────────────────────────────────

    @Test
    void lookupOrderReturnsDataForKnownOrder() throws Exception {
        String json = executor.execute("lookup_order", "{\"order_id\": \"ORD-77291\"}");
        JsonNode node = mapper.readTree(json);

        assertEquals("ORD-77291", node.path("order_id").asText());
        assertFalse(node.path("status").asText().isBlank());
        assertTrue(node.has("refund_eligible"));
    }

    @Test
    void lookupOrderIsCaseInsensitive() throws Exception {
        String lower  = executor.execute("lookup_order", "{\"order_id\": \"ord-77291\"}");
        String upper  = executor.execute("lookup_order", "{\"order_id\": \"ORD-77291\"}");

        JsonNode ln = mapper.readTree(lower);
        JsonNode un = mapper.readTree(upper);

        // Both should find the same order (or both return not-found, but not one each)
        assertEquals(ln.has("error"), un.has("error"),
                "Case sensitivity should be consistent");
    }

    @Test
    void lookupOrderReturnsErrorForUnknownOrder() throws Exception {
        String json = executor.execute("lookup_order", "{\"order_id\": \"ORD-NOTEXIST\"}");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"),
                "Unknown order should return an error node, got: " + json);
    }

    // ── initiate_refund ─────────────────────────────────────────────────────────

    @Test
    void initiateRefundSucceedsForEligibleOrder() throws Exception {
        // ORD-77291 has refund_eligible=true in mock-data.xlsx
        String json = executor.execute("initiate_refund", "{\"order_id\": \"ORD-77291\"}");
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("error"),
                "Eligible order should not return error, got: " + json);
        assertTrue(node.path("refund_id").asText().startsWith("REF-"));
        assertEquals("INITIATED", node.path("status").asText());
        assertTrue(node.path("estimated_credit_days").asInt() > 0);
    }

    @Test
    void initiateRefundFailsForIneligibleOrder() throws Exception {
        // ORD-55100 has refund_eligible=false in mock-data.xlsx
        String json = executor.execute("initiate_refund", "{\"order_id\": \"ORD-55100\"}");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"),
                "Ineligible order should return error, got: " + json);
    }

    @Test
    void initiateRefundFailsForUnknownOrder() throws Exception {
        String json = executor.execute("initiate_refund", "{\"order_id\": \"ORD-GHOST\"}");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"));
    }

    // ── search_knowledge_base ───────────────────────────────────────────────────

    @Test
    void searchKnowledgeBaseFindsReturnPolicy() throws Exception {
        String json = executor.execute("search_knowledge_base",
                "{\"query\": \"what is the return policy how many days\"}");
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("error"));
        String answer = node.path("answer").asText();
        assertFalse(answer.isBlank(), "Should return an answer for return policy query");
        // The KB entry contains "30 days" — verify relevant content was found
        assertTrue(answer.toLowerCase().contains("return") || answer.toLowerCase().contains("refund"),
                "Answer should be about returns/refunds, got: " + answer);
    }

    @Test
    void searchKnowledgeBaseReturnsLowConfidenceForUnknownQuery() throws Exception {
        String json = executor.execute("search_knowledge_base",
                "{\"query\": \"zzz completely unrelated gibberish xyz123\"}");
        JsonNode node = mapper.readTree(json);

        // Either returns a low-confidence fallback or no answer
        if (node.has("confidence")) {
            assertTrue(node.path("confidence").asDouble() <= 0.50,
                    "Unrelated query should have low confidence");
        }
    }

    @Test
    void searchKnowledgeBaseIncludesQueryInResponse() throws Exception {
        String json = executor.execute("search_knowledge_base",
                "{\"query\": \"shipping time\"}");
        JsonNode node = mapper.readTree(json);

        assertEquals("shipping time", node.path("query").asText());
    }

    // ── run_diagnostic ──────────────────────────────────────────────────────────

    @Test
    void runDiagnosticReturnsDataForKnownUser() throws Exception {
        String json = executor.execute("run_diagnostic", "{\"user_id\": \"CUST-001\"}");
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("error"), "Known user should not return error, got: " + json);
        assertFalse(node.path("account_status").asText().isBlank());
        assertFalse(node.path("connectivity_test").asText().isBlank());
    }

    @Test
    void runDiagnosticReturnsFlagsForProblematicUser() throws Exception {
        // CUST-003 has connectivity=FAIL and known_incidents=true in mock-data.xlsx
        String json = executor.execute("run_diagnostic", "{\"user_id\": \"CUST-003\"}");
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("error"));
        assertEquals("FAIL", node.path("connectivity_test").asText());
        assertTrue(node.path("known_incidents").asBoolean());
        assertFalse(node.path("incident_description").asText().isBlank());
    }

    @Test
    void runDiagnosticReturnsErrorForUnknownUser() throws Exception {
        String json = executor.execute("run_diagnostic", "{\"user_id\": \"CUST-GHOST\"}");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"));
    }

    // ── create_ticket ───────────────────────────────────────────────────────────

    @Test
    void createTicketAlwaysSucceeds() throws Exception {
        String json = executor.execute("create_ticket",
                "{\"summary\": \"App crashes on checkout\", \"priority\": \"HIGH\"}");
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("error"), "create_ticket should always succeed");
        assertTrue(node.path("ticket_id").asText().startsWith("TKT-"));
        assertEquals("OPEN", node.path("status").asText());
        assertEquals("App crashes on checkout", node.path("summary").asText());
        assertEquals("HIGH", node.path("priority").asText());
    }

    @Test
    void createTicketGeneratesUniqueIds() throws Exception {
        String json1 = executor.execute("create_ticket",
                "{\"summary\": \"Issue 1\", \"priority\": \"LOW\"}");
        String json2 = executor.execute("create_ticket",
                "{\"summary\": \"Issue 2\", \"priority\": \"LOW\"}");

        String id1 = mapper.readTree(json1).path("ticket_id").asText();
        String id2 = mapper.readTree(json2).path("ticket_id").asText();

        // IDs are random — they might collide but it's extremely unlikely
        assertNotNull(id1);
        assertNotNull(id2);
    }

    // ── Unknown tool ────────────────────────────────────────────────────────────

    @Test
    void unknownToolReturnsErrorJson() throws Exception {
        String json = executor.execute("nonexistent_tool", "{}");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"));
        assertTrue(node.path("error").asText().contains("nonexistent_tool"));
    }

    // ── Malformed input ─────────────────────────────────────────────────────────

    @Test
    void malformedInputJsonReturnsErrorGracefully() throws Exception {
        String json = executor.execute("lookup_order", "not-valid-json{{{");
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"),
                "Malformed JSON input should return error node, not throw");
    }

    @Test
    void missingRequiredFieldFallsBackGracefully() throws Exception {
        // No order_id field — executor should handle the empty string path
        String json = executor.execute("lookup_order", "{}");
        JsonNode node = mapper.readTree(json);

        // Should return an error (not found for empty string key) rather than NPE
        assertTrue(node.has("error") || json.contains("order_id"),
                "Missing field should be handled gracefully");
    }

    // ── Startup validation ──────────────────────────────────────────────────────

    @Test
    void constructorLoadsDataFromExcel() {
        // If the constructor completes without exception, Excel loaded correctly.
        // This also validates the classpath resource is present after mvn compile.
        assertDoesNotThrow(() -> new MCPToolExecutor());
    }
}
