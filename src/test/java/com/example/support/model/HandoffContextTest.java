package com.example.support.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffContextTest {

    @Test
    void ofFactoryPopulatesAllFields() {
        CustomerInquiry inquiry = new CustomerInquiry(
                "CUST-X", "ORD-1", "help", CustomerInquiry.InquiryType.ORDER);
        SubAgentResult result = new SubAgentResult(AgentRole.ORDER, "answer", 0.5, 1);
        List<SubAgentResult> results = List.of(result);

        HandoffContext ctx = HandoffContext.of(inquiry, results, "Review this", HandoffContext.Priority.HIGH);

        assertSame(inquiry,  ctx.inquiry());
        assertSame(results,  ctx.subAgentSummaries());
        assertEquals("Review this",             ctx.recommendedNextSteps());
        assertEquals(HandoffContext.Priority.HIGH, ctx.priority());
    }

    @Test
    void allPriorityLevelsExist() {
        assertNotNull(HandoffContext.Priority.valueOf("HIGH"));
        assertNotNull(HandoffContext.Priority.valueOf("MEDIUM"));
        assertNotNull(HandoffContext.Priority.valueOf("LOW"));
    }
}
