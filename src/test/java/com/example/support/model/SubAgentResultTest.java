package com.example.support.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentResultTest {

    @Test
    void constructorAndAccessors() {
        SubAgentResult result = new SubAgentResult(AgentRole.FAQ, "Great answer", 0.88, 0);

        assertEquals(AgentRole.FAQ, result.agentRole());
        assertEquals("Great answer", result.answer());
        assertEquals(0.88, result.confidence(), 1e-9);
        assertEquals(0, result.issuesDetected());
    }

    @Test
    void lowConfidenceFactoryReturnsBelowThreshold() {
        SubAgentResult low = SubAgentResult.lowConfidence(AgentRole.ORDER, "max tool rounds exceeded");

        assertEquals(AgentRole.ORDER, low.agentRole());
        assertTrue(low.confidence() < 0.75,
                "lowConfidence must be below escalation threshold (0.75)");
        assertEquals(1, low.issuesDetected(),
                "lowConfidence should mark at least one issue to aid complexity detection");
        assertTrue(low.answer().contains("max tool rounds exceeded"));
    }

    @Test
    void lowConfidenceWorksForAllRoles() {
        for (AgentRole role : AgentRole.values()) {
            SubAgentResult r = SubAgentResult.lowConfidence(role, "test");
            assertEquals(role, r.agentRole());
            assertTrue(r.confidence() < 0.75);
        }
    }
}
