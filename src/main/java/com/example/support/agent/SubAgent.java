package com.example.support.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.example.support.mcp.MCPToolExecutor;
import com.example.support.mcp.MCPToolRegistry;
import com.example.support.model.AgentRole;
import com.example.support.model.CustomerInquiry;
import com.example.support.model.SubAgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A focused subagent responsible for one domain (FAQ, ORDER, or TECHNICAL).
 *
 * <hr>
 * <b>EXAM CONCEPT — Tool-Use Loop Mechanics</b>
 * <p>
 * This class implements the core agentic pattern: a loop where Claude can call
 * tools, observe results, and call more tools before giving a final answer.
 *
 * <p>The loop works as follows:
 * <ol>
 *   <li>Call the API with {@code messages[]} and {@code tools[]}</li>
 *   <li>If {@code stop_reason == "tool_use"}:
 *     <ol type="a">
 *       <li>Extract all {@link ToolUseBlock} items from {@code response.content()}</li>
 *       <li>Execute each tool via {@link MCPToolExecutor}</li>
 *       <li>Append the ASSISTANT turn (full {@code response.content()}) to {@code messages[]}</li>
 *       <li>Append a USER turn wrapping ALL tool results to {@code messages[]}</li>
 *       <li>Call the API again — loop back to step 1</li>
 *     </ol>
 *   </li>
 *   <li>If {@code stop_reason == "end_turn"}: extract the text response and return</li>
 *   <li>{@code MAX_TOOL_ROUNDS} cap prevents runaway loops</li>
 * </ol>
 *
 * <hr>
 * <b>EXAM CONCEPT — Stateless API / Full Message History</b>
 * <p>
 * The Anthropic API is completely stateless — there is no server-side session.
 * Every API call must include the FULL conversation history from the beginning.
 * This is why {@code messages} is a growing list: each round adds two entries
 * (the assistant's tool_use turn, and the user's tool_result turn).
 *
 * <p>Omitting either the {@code tool_use} block or its matching {@code tool_result}
 * causes a {@code 400 invalid_request_error} on the next API call.
 *
 * <hr>
 * <b>EXAM CONCEPT — SDK Union Types (ContentBlock)</b>
 * <p>
 * {@link ContentBlock} is a union type (a Kotlin sealed class compiled to Java).
 * To access a specific variant, use the discriminator methods:
 * <ul>
 *   <li>{@code block.isToolUse()} / {@code block.toolUse().get()} — tool invocation</li>
 *   <li>{@code block.isText()} / {@code block.text().get().text()} — text response</li>
 * </ul>
 * Do NOT use {@code instanceof} — the variants are not Java subtypes.
 */
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    /**
     * Safety cap: prevents infinite tool loops if a tool keeps returning
     * ambiguous results that cause Claude to call another tool endlessly.
     * In practice, well-designed tools resolve in 1–2 rounds.
     */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final AnthropicClient client;
    private final AgentRole role;
    private final String systemPrompt;
    private final List<ToolUnion> tools;
    private final MCPToolExecutor toolExecutor;

    public SubAgent(AnthropicClient client, AgentRole role, MCPToolRegistry registry,
                    MCPToolExecutor toolExecutor) {
        this.client       = client;
        this.role         = role;
        this.tools        = registry.buildToolsFor(role).stream()
                .map(ToolUnion::ofTool)
                .toList();
        this.toolExecutor = toolExecutor;
        this.systemPrompt = buildSystemPrompt(role);
    }

    /**
     * Handles a customer inquiry by running the tool-use loop until resolved or capped.
     *
     * @param inquiry the customer inquiry to handle
     * @return a {@link SubAgentResult} with the final answer and confidence score
     */
    public SubAgentResult handle(CustomerInquiry inquiry) {
        log.info("[{}] Handling inquiry for customer={}", role, inquiry.customerId());

        // Seed the conversation with the customer's inquiry
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(buildUserPrompt(inquiry)))
                .build());

        // ── TOOL-USE LOOP ──────────────────────────────────────────────────────
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.debug("[{}] API call round {}", role, round + 1);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_5)
                    .system(systemPrompt)
                    .maxTokens(1024L)
                    .tools(tools)
                    .messages(messages)
                    .build();

            Message response = client.messages().create(params);

            // stopReason() returns Optional<StopReason> — use .orElse(null) for safe comparison
            StopReason stopReason = response.stopReason().orElse(null);
            log.debug("[{}] stop_reason={}", role, stopReason);

            // ── CASE 1: Claude is done — extract the final text answer ──────────
            if (stopReason == StopReason.END_TURN) {
                String answer     = extractText(response);
                double confidence = estimateConfidence(answer);
                log.info("[{}] Resolved — confidence={}", role, String.format("%.2f", confidence));
                return new SubAgentResult(role, answer, confidence, 0);
            }

            // ── CASE 2: Claude wants to call tools ─────────────────────────────
            if (stopReason == StopReason.TOOL_USE) {
                // Step 2a: collect all tool_use blocks from the response
                List<ContentBlock> assistantContent = response.content();
                List<ToolResultBlockParam> toolResults = new ArrayList<>();

                for (ContentBlock block : assistantContent) {
                    if (block.isToolUse()) {
                        // EXAM NOTE: ContentBlock is a union type — use .isToolUse() / .toolUse().get()
                        // NOT instanceof. The ToolUseBlock is accessed via the Optional accessor.
                        ToolUseBlock toolUse = block.toolUse().get();

                        log.debug("[{}] Executing tool: {} with input: {}",
                                role, toolUse.name(), toolUse._input());

                        // Step 2b: execute the tool — returns JSON string
                        // _input() returns JsonValue (the raw JSON object Claude produced)
                        String inputJson  = toolUse._input().toString();
                        String resultJson = toolExecutor.execute(toolUse.name(), inputJson);

                        toolResults.add(ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(ToolResultBlockParam.Content.ofString(resultJson))
                                .build());
                    }
                }

                // Step 2c: append the assistant's FULL tool_use turn to history.
                // CRITICAL: Convert ContentBlock list → ContentBlockParam list via .toParam().
                // The API requires the assistant turn to be in param (request) format.
                // Omitting this turn causes a 400 error on the next API call.
                List<ContentBlockParam> assistantBlockParams = assistantContent.stream()
                        .map(ContentBlock::toParam)
                        .toList();

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(MessageParam.Content.ofBlockParams(assistantBlockParams))
                        .build());

                // Step 2d: append ALL tool results as a single USER turn.
                // CRITICAL: Every tool_use id in the assistant turn must have a
                // matching tool_result in this turn. Missing results cause a 400 error.
                List<ContentBlockParam> toolResultParams = toolResults.stream()
                        .map(ContentBlockParam::ofToolResult)
                        .toList();

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofBlockParams(toolResultParams))
                        .build());

                // Continue — loop back and call the API again with extended history
                continue;
            }

            // ── CASE 3: Unexpected stop reason — escalate defensively ──────────
            log.warn("[{}] Unexpected stop_reason={} — returning low-confidence result",
                    role, stopReason);
            return SubAgentResult.lowConfidence(role,
                    "unexpected stop_reason: " + stopReason);
        }

        // Hit the MAX_TOOL_ROUNDS cap — escalate rather than loop forever
        log.warn("[{}] Hit MAX_TOOL_ROUNDS={} — returning low-confidence result",
                role, MAX_TOOL_ROUNDS);
        return SubAgentResult.lowConfidence(role, "max tool rounds exceeded");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Extracts the first text block from the response content.
     *
     * <p>Uses the union type discriminator {@code isText()} and the accessor
     * {@code text().get().text()} — see class-level Javadoc for pattern explanation.
     */
    private String extractText(Message response) {
        return response.content().stream()
                .filter(ContentBlock::isText)
                .map(b -> b.text().get().text())
                .findFirst()
                .orElse("No response generated.");
    }

    /**
     * Heuristic confidence estimator.
     *
     * <p>In production you would ask Claude to output a structured JSON response
     * including an explicit confidence score, e.g.:
     * <pre>{@code
     * {"answer": "...", "confidence": 0.92, "sources": ["returns-policy"]}
     * }</pre>
     * For this demo, length and hedging language serve as proxies.
     */
    private double estimateConfidence(String answer) {
        if (answer.length() < 50)                                          return 0.40;
        if (answer.contains("I'm not sure") || answer.contains("I cannot")) return 0.50;
        if (answer.length() > 200)                                         return 0.88;
        return 0.75;
    }

    private String buildUserPrompt(CustomerInquiry inquiry) {
        return """
                Customer ID: %s
                Order ID: %s

                Customer message:
                "%s"

                Please use your available tools to gather information, then provide a
                helpful, accurate response to the customer.
                """.formatted(
                inquiry.customerId(),
                inquiry.orderId() != null ? inquiry.orderId() : "N/A",
                inquiry.text()
        );
    }

    /**
     * Builds a role-specific system prompt that constrains agent behavior.
     *
     * <p><b>EXAM CONCEPT — System Prompt Design:</b><br>
     * System prompts are the primary mechanism for controlling subagent behavior.
     * Each prompt enforces a tool-first approach ("always call X first") and includes
     * explicit safety rules. Prompt design and tool scoping work together as
     * complementary safety layers — scoping enforces constraints structurally,
     * prompts reinforce them semantically.
     */
    private String buildSystemPrompt(AgentRole role) {
        return switch (role) {
            case FAQ -> """
                    You are a customer support FAQ specialist for an e-commerce store.
                    Your job: answer general product questions, return policies, and shipping information.

                    RULES:
                    - Always search the knowledge base before answering — never guess or fabricate policy.
                    - Be concise, friendly, and direct.
                    - If the knowledge base has no relevant entry, say so honestly.
                    """;

            case ORDER -> """
                    You are a customer support order specialist for an e-commerce store.
                    Your job: look up order status, handle tracking inquiries, and process refunds.

                    RULES:
                    - Always call lookup_order first to verify facts before responding.
                    - Only initiate a refund if: (1) the customer explicitly requested one,
                      AND (2) lookup_order confirms refund_eligible=true.
                    - Never guess order status. Never promise a refund that isn't confirmed eligible.
                    """;

            case TECHNICAL -> """
                    You are a customer support technical specialist for an e-commerce store.
                    Your job: diagnose account and connectivity issues, escalate unresolvable ones.

                    RULES:
                    - Always run run_diagnostic before advising — never assume the cause.
                    - If the issue cannot be resolved automatically, create a support ticket
                      with an accurate priority and a clear, actionable summary.
                    - LOW: cosmetic issues. MEDIUM: degraded experience. HIGH: blocked user.
                      CRITICAL: data loss or security concern.
                    """;
        };
    }
}
