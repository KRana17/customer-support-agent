package com.example.support.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EscalationDecisionTest {

    @Test
    void resolveCreatesResolvedVariant() {
        EscalationDecision decision = EscalationDecision.resolve("Here is your answer.");

        assertInstanceOf(EscalationDecision.Resolved.class, decision);
        assertEquals("Here is your answer.", ((EscalationDecision.Resolved) decision).response());
    }

    @Test
    void escalateCreatesEscalatedVariant() {
        HandoffContext ctx = buildContext();
        EscalationDecision decision = EscalationDecision.escalate(ctx);

        assertInstanceOf(EscalationDecision.Escalated.class, decision);
        assertSame(ctx, ((EscalationDecision.Escalated) decision).context());
    }

    @Test
    void patternMatchSwitchIsExhaustive() {
        // Verifies the sealed interface works correctly with pattern matching —
        // the exam-critical mechanic demonstrated in SupportAgentRunner.
        EscalationDecision resolved  = EscalationDecision.resolve("ok");
        EscalationDecision escalated = EscalationDecision.escalate(buildContext());

        assertEquals("RESOLVED",  classify(resolved));
        assertEquals("ESCALATED", classify(escalated));
    }

    private String classify(EscalationDecision decision) {
        return switch (decision) {
            case EscalationDecision.Resolved  r -> "RESOLVED";
            case EscalationDecision.Escalated e -> "ESCALATED";
        };
    }

    private HandoffContext buildContext() {
        CustomerInquiry inquiry = new CustomerInquiry(
                "CUST-001", null, "help", CustomerInquiry.InquiryType.FAQ);
        return HandoffContext.of(inquiry, List.of(), "review needed", HandoffContext.Priority.LOW);
    }
}
