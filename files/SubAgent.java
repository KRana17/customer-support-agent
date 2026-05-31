package com.example.support.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.example.support.mcp.MCPToolExecutor;
import com.example.support.mcp.MCPToolRegistry;
import com.example.support.model.AgentRole;
import com.example.support.model.CustomerInquiry;
import com.example.support.model.SubAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A focused subagent responsible for one domain (FAQ, ORDER, or TECHNICAL).
 *
 * TOOL-USE LOOP (exam-critical mechanic):
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Call the API with messages[] and tools[]
 * 2. If stop_reason == "tool_use":
 *    a. Extract all ToolUseBlock items from response.content()
 *    b. Execute each tool via MCPToolExecutor
 *    c. Append the ASSISTANT turn (response.content()) to messages[]
 *    d. Append a USER turn wrapping ALL tool results to messages[]
 *    e. Call the API again — loop back to step 1
 * 3. If stop_reason == "end_turn": extract the text response and return
 * 4. MAX_TOOL_ROUNDS cap prevents runaway loops
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Why rebuild messages[] on each round?
 * The Anthropic API is stateless. Every call needs the full conversation
 * history from the start. The tool_use block from step 2c and the
 * tool_result block from step 2d are both REQUIRED — omitting either
 * causes a 400 "invalid request" error.
 */
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Safety cap: prevent infinite tool loops on misbehaving tools. */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final AnthropicClient client;
    private final AgentRole role;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final MCPToolExecutor toolExecutor;

    public SubAgent(AnthropicClient client, AgentRole role, MCPToolRegistry registry,
                    MCPToolExecutor toolExecutor) {
        this.client = client;
        this.role = role;
        this.tools = registry.buildToolsFor(role);
        this.toolExecutor = toolExecutor;
        this.systemPrompt = buildSystemPrompt(role);
    }

    public SubAgentResult handle(CustomerInquiry inquiry) {
        log.info("[{}] Handling inquiry for customer={}", role, inquiry.customerId());

        // Seed the conversation with the user's inquiry
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(
                    buildUserPrompt(inquiry)
                ))
                .build());

        // ── TOOL-USE LOOP ──────────────────────────────────────────────────
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.debug("[{}] API call round {}", role, round + 1);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_5)   // fast + capable for subagent work
                    .system(systemPrompt)
                    .maxTokens(1024L)
                    .tools(tools)
                    .messages(messages)
                    .build();

            Message response = client.messages().create(params);

            log.debug("[{}] stop_reason={}", role, response.stopReason());

            // ── CASE 1: Done — extract the final text response ─────────────
            if (response.stopReason() == StopReason.END_TURN) {
                String answer = extractText(response);
                double confidence = estimateConfidence(answer);
                log.info("[{}] Resolved — confidence={:.2f}", role, confidence);
                return new SubAgentResult(role, answer, confidence, 0);
            }

            // ── CASE 2: Tool use requested ─────────────────────────────────
            if (response.stopReason() == StopReason.TOOL_USE) {
                // Step 2a: collect all tool_use blocks
                List<ContentBlock> assistantContent = response.content();
                List<ToolResultBlockParam> toolResults = new ArrayList<>();

                for (ContentBlock block : assistantContent) {
                    if (block instanceof ContentBlock.ToolUse toolUse) {
                        // Step 2b: execute the tool
                        String inputJson = toolUse.input().toString();
                        String resultJson = toolExecutor.execute(toolUse.name(), inputJson);

                        // Step 2b cont: wrap result for next API call
                        toolResults.add(ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(ToolResultBlockParam.Content.ofString(resultJson))
                                .build());
                    }
                }

                // Step 2c: append the assistant's full tool_use turn to history
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(MessageParam.Content.ofContentBlockList(assistantContent))
                        .build());

                // Step 2d: append ALL tool results as a single user turn
                // CRITICAL: Every tool_use id must have a matching tool_result.
                // Missing a result causes a 400 error on the next API call.
                List<ContentBlockParam> toolResultContent = toolResults.stream()
                        .map(tr -> ContentBlockParam.ofToolResult(tr))
                        .toList();

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofContentBlockParamList(toolResultContent))
                        .build());

                // Loop — call the API again with the extended messages[]
                continue;
            }

            // ── CASE 3: Unexpected stop reason ─────────────────────────────
            log.warn("[{}] Unexpected stop_reason={}, returning low-confidence",
                    role, response.stopReason());
            return SubAgentResult.lowConfidence(role, "unexpected stop_reason: " + response.stopReason());
        }

        // Hit the MAX_TOOL_ROUNDS cap — escalate defensively
        log.warn("[{}] Hit MAX_TOOL_ROUNDS={} — returning low-confidence result", role, MAX_TOOL_ROUNDS);
        return SubAgentResult.lowConfidence(role, "max tool rounds exceeded");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String extractText(Message response) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .findFirst()
                .orElse("No response generated.");
    }

    /**
     * Simple heuristic: long, specific answers score high; short or hedging answers score low.
     * In production: ask Claude to output confidence as structured JSON.
     */
    private double estimateConfidence(String answer) {
        if (answer.length() < 50)  return 0.4;
        if (answer.contains("I'm not sure") || answer.contains("I cannot")) return 0.5;
        if (answer.length() > 200) return 0.88;
        return 0.75;
    }

    private String buildUserPrompt(CustomerInquiry inquiry) {
        return """
                Customer ID: %s
                Order ID: %s
                
                Customer message:
                "%s"
                
                Please use your available tools to gather information, then provide a
                helpful, accurate response. Include your confidence level at the end
                as: CONFIDENCE: [0.0-1.0]
                """.formatted(
                inquiry.customerId(),
                inquiry.orderId() != null ? inquiry.orderId() : "N/A",
                inquiry.text()
        );
    }

    private String buildSystemPrompt(AgentRole role) {
        return switch (role) {
            case FAQ -> """
                    You are a customer support FAQ specialist. Your job is to answer
                    general product questions, return policies, and shipping information
                    accurately using the knowledge base search tool.
                    Always search the knowledge base before answering — never guess.
                    Be concise, friendly, and direct.
                    """;
            case ORDER -> """
                    You are a customer support order specialist. Your job is to look up
                    order status, handle tracking inquiries, and process refunds when appropriate.
                    Always call lookup_order first to verify facts before responding.
                    Never initiate a refund unless explicitly requested AND refund_eligible is true.
                    """;
            case TECHNICAL -> """
                    You are a customer support technical specialist. Your job is to diagnose
                    account and connectivity issues. Always run diagnostics before advising.
                    If the issue cannot be resolved automatically, create a support ticket
                    with an accurate priority level and clear summary.
                    """;
        };
    }
}
