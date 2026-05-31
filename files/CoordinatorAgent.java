package com.example.support.coordinator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.example.support.agent.SubAgent;
import com.example.support.escalation.EscalationEvaluator;
import com.example.support.mcp.MCPToolExecutor;
import com.example.support.mcp.MCPToolRegistry;
import com.example.support.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The top-level coordinator agent.
 *
 * Responsibilities:
 * 1. Classify the inquiry to determine which subagents are needed
 * 2. Fan out to relevant subagents in PARALLEL using CompletableFuture
 * 3. Fan in — collect all results
 * 4. Delegate escalation decision to EscalationEvaluator
 * 5. Return the final EscalationDecision to the caller
 *
 * The coordinator does NOT do domain work itself. It routes and aggregates.
 *
 * PARALLEL EXECUTION DESIGN:
 * ────────────────────────────────────────────────────────────────────────
 * Each subagent.handle() call blocks on HTTP to the Anthropic API.
 * With sequential calls: total time = FAQ_time + ORDER_time + TECH_time.
 * With parallel calls:   total time = max(FAQ_time, ORDER_time, TECH_time).
 *
 * Java 21 virtual threads make this easy — blocking I/O in a virtual thread
 * is cheap (no OS thread blocked), so we can launch one per subagent.
 */
public class CoordinatorAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);

    private final AnthropicClient client;
    private final SubAgent faqAgent;
    private final SubAgent orderAgent;
    private final SubAgent technicalAgent;
    private final EscalationEvaluator escalationEvaluator;

    /**
     * One shared executor for subagent parallelism.
     * Virtual threads (Java 21): one blocking API call = one cheap virtual thread.
     */
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public CoordinatorAgent() {
        // Client reads ANTHROPIC_API_KEY from environment automatically
        this.client = AnthropicOkHttpClient.fromEnv();

        MCPToolRegistry registry = new MCPToolRegistry();
        MCPToolExecutor toolExecutor = new MCPToolExecutor();

        this.faqAgent       = new SubAgent(client, AgentRole.FAQ,       registry, toolExecutor);
        this.orderAgent     = new SubAgent(client, AgentRole.ORDER,     registry, toolExecutor);
        this.technicalAgent = new SubAgent(client, AgentRole.TECHNICAL, registry, toolExecutor);
        this.escalationEvaluator = new EscalationEvaluator();
    }

    /**
     * Main entry point. Accepts a CustomerInquiry and returns an EscalationDecision.
     */
    public EscalationDecision handle(CustomerInquiry inquiry) {
        log.info("Coordinator received inquiry — customer={} type={}",
                inquiry.customerId(), inquiry.type());

        // Step 1: classify which subagents are needed
        List<SubAgent> relevantAgents = classify(inquiry);
        log.info("Routing to agents: {}", relevantAgents.size());

        // Step 2: fan-out — launch all subagents in parallel
        List<CompletableFuture<SubAgentResult>> futures = relevantAgents.stream()
                .map(agent -> CompletableFuture.supplyAsync(
                        () -> agent.handle(inquiry),
                        executor
                ))
                .toList();

        // Step 3: fan-in — wait for ALL results
        // CompletableFuture.allOf blocks until every future completes (or fails)
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        List<SubAgentResult> results = allDone.thenApply(v ->
                futures.stream()
                       .map(CompletableFuture::join)   // join is safe here — all done
                       .toList()
        ).join();

        log.info("All subagents completed — {} results collected", results.size());

        // Step 4: escalation decision
        return escalationEvaluator.evaluate(results, inquiry);
    }

    /**
     * Classifies the inquiry and returns the relevant subagents.
     *
     * In a production system: make a fast Claude call with a classifier prompt,
     * or use a keyword/ML classifier. For the exam project: rule-based.
     *
     * Deliberately returns MULTIPLE agents for cross-cutting inquiries —
     * e.g. "my order is late and your app is broken" → ORDER + TECHNICAL.
     */
    private List<SubAgent> classify(CustomerInquiry inquiry) {
        List<SubAgent> agents = new ArrayList<>();
        String text = inquiry.text().toLowerCase();

        boolean orderRelated = text.contains("order") || text.contains("refund")
                || text.contains("shipping") || text.contains("delivery")
                || inquiry.orderId() != null;

        boolean technicalIssue = text.contains("broken") || text.contains("error")
                || text.contains("crash") || text.contains("not working")
                || text.contains("can't log") || text.contains("cannot log");

        boolean generalQuestion = text.contains("how") || text.contains("what")
                || text.contains("policy") || text.contains("return")
                || text.contains("when") || (!orderRelated && !technicalIssue);

        if (orderRelated)    agents.add(orderAgent);
        if (technicalIssue)  agents.add(technicalAgent);
        if (generalQuestion) agents.add(faqAgent);

        // Always have at least one agent
        if (agents.isEmpty()) agents.add(faqAgent);

        return agents;
    }

    @Override
    public void close() {
        executor.shutdown();
        client.close();
    }
}
