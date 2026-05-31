package com.example.support.mcp;

import com.anthropic.models.messages.Tool;
import com.example.support.model.AgentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MCPToolRegistry.
 *
 * EXAM CONCEPT — Role-scoped tool access:
 * These tests verify that each agent role receives exactly the tools it should have
 * and NONE of the tools it shouldn't. Safety through structural enforcement.
 */
class MCPToolRegistryTest {

    private MCPToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MCPToolRegistry();
    }

    // ── Role → tool mapping ────────────────────────────────────────────────────

    @Test
    void faqAgentReceivesOnlySearchKnowledgeBase() {
        List<Tool> tools = registry.buildToolsFor(AgentRole.FAQ);

        assertEquals(1, tools.size());
        assertEquals("search_knowledge_base", tools.get(0).name());
    }

    @Test
    void orderAgentReceivesLookupAndRefund() {
        List<Tool> tools = registry.buildToolsFor(AgentRole.ORDER);

        assertEquals(2, tools.size());
        List<String> names = tools.stream().map(Tool::name).toList();
        assertTrue(names.contains("lookup_order"));
        assertTrue(names.contains("initiate_refund"));
    }

    @Test
    void technicalAgentReceivesDiagnosticAndTicket() {
        List<Tool> tools = registry.buildToolsFor(AgentRole.TECHNICAL);

        assertEquals(2, tools.size());
        List<String> names = tools.stream().map(Tool::name).toList();
        assertTrue(names.contains("run_diagnostic"));
        assertTrue(names.contains("create_ticket"));
    }

    // ── Safety: cross-role tool isolation ─────────────────────────────────────

    @Test
    void faqAgentCannotAccessInitiateRefund() {
        List<String> names = toolNames(AgentRole.FAQ);
        assertFalse(names.contains("initiate_refund"),
                "FAQ agent must NOT have access to initiate_refund");
    }

    @Test
    void faqAgentCannotAccessLookupOrder() {
        List<String> names = toolNames(AgentRole.FAQ);
        assertFalse(names.contains("lookup_order"),
                "FAQ agent must NOT have access to lookup_order");
    }

    @Test
    void orderAgentCannotAccessDiagnosticTools() {
        List<String> names = toolNames(AgentRole.ORDER);
        assertFalse(names.contains("run_diagnostic"));
        assertFalse(names.contains("create_ticket"));
    }

    @Test
    void technicalAgentCannotAccessOrderTools() {
        List<String> names = toolNames(AgentRole.TECHNICAL);
        assertFalse(names.contains("lookup_order"));
        assertFalse(names.contains("initiate_refund"));
    }

    // ── Schema completeness ────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(AgentRole.class)
    void allToolsHaveNonBlankDescriptions(AgentRole role) {
        List<Tool> tools = registry.buildToolsFor(role);
        for (Tool tool : tools) {
            assertFalse(tool.description().orElse("").isBlank(),
                    tool.name() + " is missing a description");
        }
    }

    @ParameterizedTest
    @EnumSource(AgentRole.class)
    void allToolsHaveNonBlankNames(AgentRole role) {
        List<Tool> tools = registry.buildToolsFor(role);
        for (Tool tool : tools) {
            assertFalse(tool.name().isBlank(), "Tool has a blank name for role " + role);
        }
    }

    @ParameterizedTest
    @EnumSource(AgentRole.class)
    void allToolsHaveInputSchema(AgentRole role) {
        List<Tool> tools = registry.buildToolsFor(role);
        for (Tool tool : tools) {
            assertTrue(tool.inputSchema().isValid(),
                    tool.name() + " has an invalid inputSchema");
        }
    }

    @ParameterizedTest
    @EnumSource(AgentRole.class)
    void everyRoleReturnsAtLeastOneTool(AgentRole role) {
        List<Tool> tools = registry.buildToolsFor(role);
        assertFalse(tools.isEmpty(), "Role " + role + " returned no tools");
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private List<String> toolNames(AgentRole role) {
        return registry.buildToolsFor(role).stream().map(Tool::name).toList();
    }
}
