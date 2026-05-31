package com.example.support.runner;

import com.example.support.coordinator.CoordinatorAgent;
import com.example.support.model.CustomerInquiry;
import com.example.support.model.EscalationDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ─────────────────────────────────────────────────────────────────────────
 * HOW TO RUN IN INTELLIJ
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Open Run → Edit Configurations
 * 2. Add → Application
 * 3. Main class: com.example.support.runner.SupportAgentRunner
 * 4. Environment variable: ANTHROPIC_API_KEY=sk-ant-...
 * 5. Click Run (Shift+F10)
 *
 * OR from the terminal:
 *   export ANTHROPIC_API_KEY=sk-ant-...
 *   mvn exec:java
 * ─────────────────────────────────────────────────────────────────────────
 */
public class SupportAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SupportAgentRunner.class);

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  Customer Support AI Agent — Multi-Agent Demo");
        System.out.println("═══════════════════════════════════════════════════════\n");

        // The coordinator creates the Anthropic client and all subagents
        // try-with-resources ensures the executor and client are properly shut down
        try (CoordinatorAgent coordinator = new CoordinatorAgent()) {

            // ── Demo 1: General FAQ ─────────────────────────────────────────
            runDemo(coordinator, new CustomerInquiry(
                    "CUST-001",
                    null,
                    "What is your return policy? How many days do I have to return an item?",
                    CustomerInquiry.InquiryType.FAQ
            ));

            // ── Demo 2: Order inquiry with refund ───────────────────────────
            runDemo(coordinator, new CustomerInquiry(
                    "CUST-002",
                    "ORD-77291",
                    "My order ORD-77291 still hasn't arrived. I want a refund.",
                    CustomerInquiry.InquiryType.ORDER
            ));

            // ── Demo 3: Escalation trigger (frustration language) ───────────
            runDemo(coordinator, new CustomerInquiry(
                    "CUST-003",
                    "ORD-55100",
                    "This is absolutely unacceptable! My order is wrong AND your app keeps crashing. " +
                    "I have never experienced such terrible service. I want to speak to a manager NOW.",
                    CustomerInquiry.InquiryType.UNKNOWN
            ));

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.err.println("\n❌ Error: " + e.getMessage());
            System.err.println("Make sure ANTHROPIC_API_KEY is set in your environment.");
        }
    }

    private static void runDemo(CoordinatorAgent coordinator, CustomerInquiry inquiry) {
        System.out.println("─────────────────────────────────────────────────────");
        System.out.printf("📨 Customer %s: \"%s\"%n%n", inquiry.customerId(), inquiry.text());

        try {
            EscalationDecision decision = coordinator.handle(inquiry);

            switch (decision) {
                case EscalationDecision.Resolved resolved -> {
                    System.out.println("✅ RESOLVED");
                    System.out.println("Response to customer:\n");
                    System.out.println(resolved.response());
                }
                case EscalationDecision.Escalated escalated -> {
                    System.out.println("🚨 ESCALATED TO HUMAN AGENT");
                    System.out.printf("Priority: %s%n",
                            escalated.context().priority());
                    System.out.printf("Recommended steps: %s%n",
                            escalated.context().recommendedNextSteps());
                    System.out.println("\nSubagent summaries sent to human:");
                    escalated.context().subAgentSummaries().forEach(r ->
                        System.out.printf("  [%s] confidence=%.2f — %s%n",
                                r.agentRole(), r.confidence(),
                                r.answer().substring(0, Math.min(80, r.answer().length())) + "...")
                    );
                }
            }
        } catch (Exception e) {
            System.err.printf("❌ Error handling inquiry for %s: %s%n",
                    inquiry.customerId(), e.getMessage());
            log.error("Inquiry handling failed", e);
        }

        System.out.println();
    }
}
