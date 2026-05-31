# Customer Support AI Agent — Claude Code Project Guide

## What this project is

A teaching demo for developers preparing for the **Anthropic Claude AI architect certificate exam**.
Every class and design decision is annotated to explain the underlying API concept it demonstrates.

- **Language:** Java 21
- **SDK:** Anthropic Java SDK 2.32.0
- **Build:** Maven (`pom.xml`) and Gradle (`build.gradle`) — both work
- **Data:** Apache POI reads `src/main/resources/mock-data.xlsx` at startup

## How to run

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn exec:java          # Maven
./gradlew run          # Gradle
```

⚠️ **Do NOT run without `ANTHROPIC_API_KEY` set** — `AnthropicOkHttpClient.fromEnv()` throws immediately.

## How to run tests (no API key needed)

```bash
mvn test               # 99 tests, all offline
./gradlew test
```

## Architecture

```
SupportAgentRunner → CoordinatorAgent (classify → fan-out → fan-in)
    ↓ parallel virtual threads
    FAQ SubAgent | ORDER SubAgent | TECHNICAL SubAgent
    ↓ each runs a tool-use loop (stop_reason == tool_use)
    MCPToolExecutor → mock-data.xlsx
    ↓ results collected
    EscalationEvaluator → EscalationDecision (Resolved | Escalated)
```

## The 9 Exam Concepts

| # | Concept | Key File | Test Class |
|---|---------|----------|------------|
| 1 | Tool-use loop (`stop_reason`, message history) | `SubAgent.java` | `SubAgentConfidenceTest` |
| 2 | Multi-agent orchestration (coordinator pattern) | `CoordinatorAgent.java` | `CoordinatorAgentClassifyTest` |
| 3 | Parallel execution (CompletableFuture + virtual threads) | `CoordinatorAgent.java` | `CoordinatorAgentClassifyTest` |
| 4 | Escalation / handoff pattern | `EscalationEvaluator.java` | `EscalationEvaluatorTest` |
| 5 | Structured JSON schema for tools | `MCPToolRegistry.java` | `MCPToolRegistryTest` |
| 6 | Stateless API (full message history every call) | `SubAgent.java` | `SubAgentConfidenceTest` |
| 7 | Claude in CI/CD (Direct API, CLI headless, Actions) | `.github/workflows/` | — |
| 8 | Prompt caching (`cache_control: ephemeral`) | `scripts/claude_review.py` | — |
| 9 | Structured output + exit-code merge gate | `scripts/claude_review.py` | — |

## Key SDK Types (non-obvious — exam critical)

| What you want | Correct SDK call |
|---|---|
| Check if response has tool call | `response.stopReason().orElse(null) == StopReason.TOOL_USE` |
| Get tool use block | `block.isToolUse()` then `block.toolUse().get()` |
| Get tool input JSON | `toolUse._input().toString()` |
| Get text from response | `block.isText()` then `block.text().get().text()` |
| Wrap Tool for API call | `ToolUnion.ofTool(tool)` → `List<ToolUnion>` |
| Append assistant turn | `ContentBlock::toParam` → `MessageParam.Content.ofBlockParams(...)` |
| Append tool results | `ContentBlockParam.ofToolResult(toolResultParam)` |
| Tool schema type | `JsonValue.from("object")` (not `Tool.InputSchema.Type.OBJECT`) |
| Tool schema properties | `Tool.InputSchema.Properties.builder().putAdditionalProperty(name, JsonValue.fromJsonNode(node))` |
| Tool schema required | `.required(List.of("field_name"))` |

## Available Slash Commands

| Command | Usage | What it does |
|---|---|---|
| `/concept-map` | `/concept-map` | One-page reference of all 9 concepts with exam tips |
| `/explain` | `/explain tool-use-loop` | Deep-dive into one concept with code pointers |
| `/quiz` | `/quiz escalation` | 5 exam-style questions with hidden answers |
| `/trace` | `/trace "I want a refund"` | Traces execution path through the system without running the API |
| `/mock-exam` | `/mock-exam` | Interactive 10-question timed exam with scoring |
| `/add-scenario` | `/add-scenario "locked out user"` | Scaffolds a new demo scenario in SupportAgentRunner.java |

## Project layout

```
src/main/java/com/example/support/
├── model/          AgentRole, CustomerInquiry, SubAgentResult, EscalationDecision, HandoffContext
├── coordinator/    CoordinatorAgent  (fan-out / fan-in)
├── agent/          SubAgent          (tool-use loop — the exam centrepiece)
├── escalation/     EscalationEvaluator
├── mcp/            MCPToolRegistry, MCPToolExecutor
├── runner/         SupportAgentRunner (3 demo scenarios)
└── util/           MockDataGenerator (regenerates mock-data.xlsx)

src/test/           9 test classes, 99 tests — all run offline
.github/workflows/  ci.yml, claude-pr-review.yml, claude-test-gen.yml
scripts/            claude_review.py  (prompt caching + structured output demo)
docs/               architecture-diagrams.md, cicd-integration-guide.md
```
