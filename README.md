# Customer Support AI Agent — Multi-Agent Demo

> **Audience:** Developers preparing for the Anthropic Claude AI certificate exam.
> Every class, method, and design decision in this project is annotated to explain
> the underlying API concept it demonstrates.

---

## What This Project Teaches

This demo implements a realistic multi-agent customer support pipeline using the
**Anthropic Java SDK 2.32.0**. It is deliberately over-documented so you can read
the code and understand not just *what* it does but *why* — the exact mechanics the
exam tests. The concepts demonstrated are:

| # | Concept | Where to look |
|---|---|---|
| 1 | Tool-use loop (`stop_reason`, message history) | `SubAgent.java` |
| 2 | Multi-agent orchestration (coordinator pattern) | `CoordinatorAgent.java` |
| 3 | Parallel subagent execution (virtual threads) | `CoordinatorAgent.java` |
| 4 | Escalation / handoff pattern | `EscalationEvaluator.java`, `HandoffContext.java` |
| 5 | Structured JSON schema for tools | `MCPToolRegistry.java` |
| 6 | Stateless API (full history every call) | `SubAgent.java` (loop section) |
| 7 | Claude in CI/CD (Direct API, CLI headless, Actions) | `.github/workflows/`, `scripts/claude_review.py` |
| 8 | Prompt caching (`cache_control: ephemeral`) | `scripts/claude_review.py` |
| 9 | Structured output + exit-code merge gate | `scripts/claude_review.py` |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                    SupportAgentRunner (main)                          │
│  Scenario 1: FAQ    │  Scenario 2: Order+Refund  │  Scenario 3: Escalation │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ CustomerInquiry
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       CoordinatorAgent                                │
│                                                                        │
│  1. classify() — keyword-based routing                                 │
│  2. fan-out — CompletableFuture.supplyAsync() × N agents              │
│                  (Java 21 virtual threads — cheap blocking I/O)        │
│  3. fan-in  — CompletableFuture.allOf().join()                         │
│  4. EscalationEvaluator.evaluate(results, inquiry)                     │
└────────┬──────────────────────┬────────────────────┬──────────────────┘
         │  virtual thread      │  virtual thread     │  virtual thread
         ▼                      ▼                     ▼
┌─────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐
│   FAQ SubAgent  │  │  ORDER SubAgent  │  │   TECHNICAL SubAgent     │
│                 │  │                  │  │                          │
│  Tool-use loop  │  │  Tool-use loop   │  │  Tool-use loop           │
│  (rounds 1..5)  │  │  (rounds 1..5)   │  │  (rounds 1..5)           │
└────────┬────────┘  └────────┬─────────┘  └──────────┬───────────────┘
         │                    │                        │
         ▼                    ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       MCPToolExecutor                                  │
│                                                                        │
│  search_knowledge_base │ lookup_order │ initiate_refund               │
│                        │ run_diagnostic │ create_ticket               │
└──────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │  mock-data.xlsx        │
                    │  (Apache POI reader)   │
                    │                        │
                    │  Sheet: Orders         │
                    │  Sheet: KnowledgeBase  │
                    │  Sheet: Diagnostics    │
                    └────────────────────────┘

Fan-in results → SubAgentResult[]
                       │
                       ▼
              EscalationEvaluator
                  │           │
                  ▼           ▼
         Resolved          Escalated
       (send to         (HandoffContext
        customer)        → human queue)
```

---

## Architecture Diagrams

Visual walkthroughs of every concept in this project — sequence diagrams, flowcharts,
class diagram, and CI/CD pipeline flow. All diagrams use Mermaid (rendered on GitHub).

→ **[`docs/architecture-diagrams.md`](docs/architecture-diagrams.md)**

| Diagram | What it shows |
|---|---|
| System Overview | All components and dependencies |
| Sequence: FAQ | Single agent, one tool call, resolved |
| Sequence: Order+Refund | Multi-round tool-use loop |
| Sequence: Escalation | Parallel agents + escalation path |
| Flowchart: Tool-Use Loop | Exact states inside `SubAgent.handle()` |
| Flowchart: Escalation Logic | Three signals → Resolved or Escalated |
| Class Diagram | Model layer relationships |
| Sequence: CI/CD Pipeline | PR open → Claude review → comment posted |
| Flowchart: Fan-Out/Fan-In | Virtual thread parallelism in `CoordinatorAgent` |
| Flowchart: Prompt Caching | Token cost reduction across CI calls |

---

## Key Exam Concepts — Explained

### 1. Tool-Use Loop Mechanics

The tool-use loop is the heart of any agentic application. Claude does not call tools
directly — it *requests* tool calls by returning `stop_reason = "tool_use"`. Your code
must detect this, execute the tools, and continue the conversation. See `SubAgent.java`.

**The loop:**

```
Round 1: Send messages[] + tools[] to API
         → stop_reason = "tool_use"
         → response.content() contains one or more ToolUseBlock items

         For each ToolUseBlock:
           - toolUse.name()     → which tool Claude wants
           - toolUse.id()       → unique ID you must echo back
           - toolUse._input()   → JSON arguments Claude produced

         Execute the tools locally (or via MCP server)

         Append ASSISTANT turn (the full response.content()) to messages[]
         Append USER turn (all tool results) to messages[]

Round 2: Send extended messages[] to API again
         → stop_reason = "end_turn"  (or another tool_use round)
         → extract text from response.content()
```

**Two critical rules:**
1. The assistant turn must include the FULL `response.content()` — not just the
   text parts. Omitting the `ToolUseBlock` causes a `400` error.
2. Every `toolUseId` in the assistant turn must have a matching `tool_result` in the
   next user turn. Missing any ID causes a `400` error.

**`MAX_TOOL_ROUNDS` cap:** Prevents runaway loops. If Claude calls tools more than
5 times without resolving, the agent returns a `lowConfidence` result that triggers
escalation rather than looping forever.

---

### 2. Multi-Agent Orchestration (Coordinator Pattern)

`CoordinatorAgent` is a pure router — it has no domain knowledge. Its only jobs are:
- **Classify** the inquiry (keyword-based) → decide which subagents to involve
- **Fan-out** — launch subagents in parallel
- **Fan-in** — collect results
- **Delegate** the resolution/escalation decision

Each subagent is a focused specialist:

| Agent | Tools | Responsibility |
|---|---|---|
| FAQ | `search_knowledge_base` | Product questions, policy, shipping |
| ORDER | `lookup_order`, `initiate_refund` | Order status, tracking, refunds |
| TECHNICAL | `run_diagnostic`, `create_ticket` | Account issues, app crashes |

**Role-scoped tools** enforce safety structurally: a FAQ agent literally cannot call
`initiate_refund` because that tool is never added to its tool list. Claude cannot
hallucinate a tool call for a tool it was not given.

---

### 3. Parallel Execution (CompletableFuture + Virtual Threads)

**Why parallel?** Each subagent makes one or more blocking HTTP calls to the Anthropic
API. Sequential execution means total time = sum of all agent times. Parallel means
total time ≈ the slowest single agent.

```java
// Fan-out: launch all subagents simultaneously
List<CompletableFuture<SubAgentResult>> futures = relevantAgents.stream()
    .map(agent -> CompletableFuture.supplyAsync(
        () -> agent.handle(inquiry),   // blocking HTTP call
        executor                       // virtual thread executor
    ))
    .toList();

// Fan-in: wait for ALL to complete before proceeding
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenApply(v -> futures.stream().map(CompletableFuture::join).toList())
    .join();
```

**Why virtual threads (Java 21)?** Traditional thread pools park an OS thread while
waiting for I/O. Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) are
cheap — blocking HTTP inside a virtual thread suspends only the virtual thread, not
the underlying OS thread. You can have thousands of blocked virtual threads with
minimal overhead.

---

### 4. Escalation / Handoff Pattern

After all subagents complete, `EscalationEvaluator` checks three independent signals.
Any one trigger is sufficient for escalation (OR logic):

```
Signal 1 — CONFIDENCE:  min(subagent confidences) < 0.75
Signal 2 — SENTIMENT:   inquiry text contains frustration keywords
Signal 3 — COMPLEXITY:  2+ agents detected distinct issues
```

When escalated, a `HandoffContext` is built containing:
- The original inquiry
- All subagent results (answers + confidence scores)
- A human-readable explanation of why escalation triggered
- A priority level (HIGH / MEDIUM / LOW) for queue routing

`EscalationDecision` is a **sealed interface** with two permitted records:
- `Resolved(String response)` — send response to customer
- `Escalated(HandoffContext context)` — hand off to human queue

The **pattern-matching switch** in `SupportAgentRunner` is exhaustive — the compiler
rejects unhandled cases:

```java
switch (decision) {
    case EscalationDecision.Resolved   resolved  -> { /* send response */ }
    case EscalationDecision.Escalated  escalated -> { /* hand off     */ }
}
```

---

### 5. Structured JSON Schema for Tools

Tools are defined using `Tool.InputSchema`. The schema tells Claude exactly what
parameters to provide when calling the tool.

**SDK builder pattern:**

```java
Tool.InputSchema.builder()
    .type(JsonValue.from("object"))      // schema type — always "object"
    .properties(                          // field definitions
        Tool.InputSchema.Properties.builder()
            .putAdditionalProperty(
                "order_id",
                JsonValue.fromJsonNode(fieldSchema)  // {"type":"string","description":"..."}
            )
            .build()
    )
    .required(List.of("order_id"))       // required field names
    .build()
```

**Key SDK types to know:**
- `Tool.InputSchema` — the schema object attached to each tool
- `Tool.InputSchema.Properties` — holds the field map (via additional properties)
- `JsonValue.from(Object)` — wraps any Java object as a JSON value
- `JsonValue.fromJsonNode(JsonNode)` — wraps a Jackson JsonNode as a JsonValue
- `ToolUnion.ofTool(Tool)` — wraps a `Tool` into the union type `ToolUnion` required by
  `MessageCreateParams.Builder.tools(List<ToolUnion>)`

---

### 6. Stateless API — Full History Every Call

The Anthropic Messages API has **no server-side session state**. Every API call is
independent. This has one critical implication: you must send the complete conversation
history in every request.

In `SubAgent.java`, the `messages` list grows with each round:

```
Round 1 call:   [USER: "My order hasn't arrived..."]
Round 2 call:   [USER: "...", ASSISTANT: <tool_use>, USER: <tool_result>]
Round 3 call:   [USER: "...", ASSISTANT: <tool_use>, USER: <tool_result>,
                 ASSISTANT: <tool_use2>, USER: <tool_result2>]
```

The list is rebuilt from scratch on every API call, not just the new messages.
This is by design — each call is stateless from the server's perspective.

**Exam tip:** A common mistake is to only append the latest messages. The API requires
the full history or it cannot understand the context of the conversation.

---

## Project Structure

```
src/main/java/com/example/support/
│
├── model/                          Domain objects (records and enums)
│   ├── AgentRole.java              Enum: FAQ, ORDER, TECHNICAL
│   ├── CustomerInquiry.java        Record: customerId, orderId, text, type
│   ├── SubAgentResult.java         Record: agentRole, answer, confidence, issuesDetected
│   ├── EscalationDecision.java     Sealed interface: Resolved | Escalated
│   └── HandoffContext.java         Record: inquiry, results, recommendedNextSteps, priority
│
├── coordinator/
│   └── CoordinatorAgent.java       Router: classify → fan-out → fan-in → escalate
│
├── agent/
│   └── SubAgent.java               Tool-use loop implementation (THE core exam concept)
│
├── escalation/
│   └── EscalationEvaluator.java    Confidence + sentiment + complexity signals
│
├── mcp/
│   ├── MCPToolRegistry.java        Tool definitions with JSON Schema (InputSchema builder)
│   └── MCPToolExecutor.java        Tool dispatch backed by mock-data.xlsx (Apache POI)
│
├── runner/
│   └── SupportAgentRunner.java     Entry point: runs 3 demo scenarios
│
└── util/
    └── MockDataGenerator.java      One-time utility to regenerate mock-data.xlsx

src/main/resources/
├── mock-data.xlsx                  Mock order/KB/diagnostic data (editable in Excel)
└── logback.xml                     Logging config (DEBUG for our code, WARN for SDK)
```

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java | 21+ | Required for virtual threads and sealed interfaces |
| Maven | 3.9+ | Dependency management and build |
| ANTHROPIC_API_KEY | — | Get at console.anthropic.com |

---

## How to Run

### Option 1a: Maven (terminal)

```bash
export ANTHROPIC_API_KEY=sk-ant-api03-...
cd customer-support-agent
mvn clean compile exec:java
```

### Option 1b: Gradle (terminal)

```bash
export ANTHROPIC_API_KEY=sk-ant-api03-...
cd customer-support-agent
./gradlew run
```

> **Note:** If `./gradlew` fails with a JDK error, pin Java 21 explicitly:
> ```bash
> JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew run
> ```

Other useful Gradle tasks:
```bash
./gradlew compileJava   # compile only
./gradlew test          # run JUnit tests
./gradlew clean         # delete build/ directory
```

### Option 2: IntelliJ IDEA

1. Open IntelliJ → **File → Open** → select `customer-support-agent/` (the folder with `pom.xml`)
2. Wait for Maven sync to complete
3. Open **Run → Edit Configurations**
4. Click **+** → **Application**
5. Set **Name:** `SupportAgentRunner`
6. Set **Main class:** `com.example.support.runner.SupportAgentRunner`
7. Open **Modify options → Environment variables**
8. Add: `ANTHROPIC_API_KEY=sk-ant-api03-...`
9. Click **Run** (or press `Shift+F10`)

### Regenerating Mock Data

If you delete or corrupt `mock-data.xlsx`, regenerate it:

```bash
java -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null):target/classes" \
     com.example.support.util.MockDataGenerator
```

---

## Demo Scenarios — What Each One Teaches

### Scenario 1: General FAQ (CUST-001)

```
Customer: "What is your return policy? How many days do I have to return an item?"
```

**What happens:**
1. Coordinator classifies as `FAQ` (contains "return", "policy")
2. FAQ SubAgent is launched (alone — no order/technical routing)
3. SubAgent calls `search_knowledge_base` with the query
4. Tool returns the return policy entry from the KnowledgeBase sheet
5. Claude formulates a response and returns `stop_reason = end_turn`
6. Confidence ≥ 0.75 → **✅ RESOLVED**

**Concepts shown:** Single-agent routing, 1-round tool-use loop, knowledge base search

---

### Scenario 2: Order Inquiry with Refund (CUST-002, ORD-77291)

```
Customer: "My order ORD-77291 still hasn't arrived. I want a refund."
```

**What happens:**
1. Coordinator classifies as `ORDER` (contains "order", "refund", has orderId)
2. ORDER SubAgent is launched
3. Round 1: Claude calls `lookup_order` with order_id=ORD-77291
4. Tool returns: `status=SHIPPED, refund_eligible=true` (from Orders sheet)
5. Round 2: Claude calls `initiate_refund` (customer explicitly requested it AND eligible)
6. Tool returns: `refund_id=REF-..., status=INITIATED`
7. Round 3: Claude formulates final response, `stop_reason = end_turn`
8. Confidence ≥ 0.75 → **✅ RESOLVED** (includes refund confirmation)

**Concepts shown:** Multi-round tool-use loop, conditional tool calls (refund only when eligible)

---

### Scenario 3: Escalation Trigger (CUST-003, ORD-55100)

```
Customer: "This is absolutely unacceptable! My order is wrong AND your app
           keeps crashing. I want to speak to a manager NOW."
```

**What happens:**
1. Coordinator classifies as `ORDER` + `TECHNICAL` (multi-domain)
2. Both subagents launched in parallel
3. ORDER agent: looks up ORD-55100 → `refund_eligible=false` → cannot process refund
4. TECHNICAL agent: runs diagnostic on CUST-003 → `connectivity=FAIL, known_incidents=true`
   → creates a support ticket
5. Fan-in: both results collected
6. EscalationEvaluator checks:
   - "unacceptable" → **frustration signal** ✓ (immediate HIGH priority)
   - 2 agents with issues → **complexity signal** ✓
7. **🚨 ESCALATED** — HandoffContext printed with all findings

**Concepts shown:** Parallel multi-agent execution, escalation triggers, HandoffContext

---

## Mock Data Reference

**File location:** `src/main/resources/mock-data.xlsx` — loaded by `MCPToolExecutor` at startup via Apache POI. No code changes needed to add rows; just edit the file in Excel or LibreOffice and restart.

### Sheet 1: `Orders`

| Column | Type | Allowed Values | Notes |
|---|---|---|---|
| `order_id` | String | e.g. `ORD-77291` | Lookup key — case-insensitive |
| `customer_id` | String | e.g. `CUST-002` | Links order to a customer |
| `status` | String | `SHIPPED` / `DELIVERED` / `PROCESSING` / `CANCELLED` | Returned verbatim to agent |
| `tracking_number` | String | e.g. `TRK-88291-CA` or `N/A` | |
| `estimated_delivery` | String | `YYYY-MM-DD` or `N/A` | |
| `total_amount` | Number | e.g. `149.99` | USD |
| `refund_eligible` | String | `true` or `false` | **Critical** — `initiate_refund` tool refuses if `false` |

**Seeded rows:**

| order_id | customer_id | status | tracking_number | estimated_delivery | total_amount | refund_eligible |
|---|---|---|---|---|---|---|
| ORD-77291 | CUST-002 | SHIPPED | TRK-88291-CA | 2026-06-05 | 149.99 | true |
| ORD-55100 | CUST-003 | DELIVERED | TRK-55100-XY | 2026-05-20 | 89.50 | false |
| ORD-10001 | CUST-001 | PROCESSING | N/A | 2026-06-10 | 49.99 | false |
| ORD-20020 | CUST-004 | DELIVERED | TRK-20020-ZA | 2026-05-15 | 220.00 | true |
| ORD-30030 | CUST-005 | CANCELLED | N/A | N/A | 75.00 | false |

---

### Sheet 2: `KnowledgeBase`

| Column | Type | Notes |
|---|---|---|
| `topic` | String | Unique slug, e.g. `return-policy` |
| `keywords` | String | Comma-separated — FAQ agent scores each entry by keyword overlap with the query |
| `answer` | String | Full response text returned to the agent |
| `source_url` | String | Documentation link included in tool result |
| `confidence` | Number | `0.0`–`1.0` — returned with the result; drives escalation if low |

**Search algorithm:** `MCPToolExecutor.searchKnowledgeBase()` counts how many comma-separated keywords from each entry appear in the query. The highest-scoring entry wins. Ties go to the first match.

**Seeded rows:**

| topic | keywords | confidence |
|---|---|---|
| return-policy | return,refund,policy,days,how many,can i return | 0.95 |
| shipping-times | shipping,delivery,how long,when,arrive,standard,express | 0.92 |
| payment-methods | payment,pay,credit,card,method,accept,visa,mastercard,paypal | 0.90 |
| product-warranty | warranty,guarantee,broken,defective,damaged,replace | 0.88 |
| account-creation | account,sign up,register,create,join,membership | 0.85 |
| order-cancellation | cancel,cancellation,cancel order,stop order,undo order | 0.87 |

---

### Sheet 3: `Diagnostics`

| Column | Type | Allowed Values | Notes |
|---|---|---|---|
| `user_id` | String | e.g. `CUST-003` | Must match `customerId` in the inquiry |
| `account_status` | String | `ACTIVE` / `SUSPENDED` / `LOCKED` | Returned verbatim to agent |
| `last_login` | String | ISO 8601 timestamp | e.g. `2026-05-27T09:12:00Z` |
| `connectivity_test` | String | `PASS` or `FAIL` | FAIL → agent likely creates a ticket |
| `known_incidents` | String | `true` or `false` | Signals a platform-wide issue |
| `incident_description` | String | Free text or blank | Shown to agent when `known_incidents=true` |

**Seeded rows:**

| user_id | account_status | last_login | connectivity_test | known_incidents | incident_description |
|---|---|---|---|---|---|
| CUST-001 | ACTIVE | 2026-05-29T10:00:00Z | PASS | false | |
| CUST-002 | ACTIVE | 2026-05-28T18:45:00Z | PASS | false | |
| CUST-003 | ACTIVE | 2026-05-27T09:12:00Z | FAIL | true | App crash on checkout — mobile app v2.4.1, fix in v2.4.2 (ETA 2026-06-01) |
| CUST-004 | ACTIVE | 2026-05-30T08:00:00Z | PASS | false | |
| CUST-005 | SUSPENDED | 2026-04-10T12:30:00Z | FAIL | true | Account suspended due to chargeback dispute. Connectivity blocked pending review. |

---

### Adding your own scenarios

1. Open `src/main/resources/mock-data.xlsx` in Excel or LibreOffice
2. Add rows to the relevant sheet(s)
3. Save the file
4. Restart the app — `MCPToolExecutor` reloads on each startup

### Regenerating default data

If the file is deleted or corrupted:

```bash
mvn compile
java -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null):target/classes" \
     com.example.support.util.MockDataGenerator
```

---

## Extending the Demo

### Add a New Tool

1. **Register it** in `MCPToolRegistry.buildToolsFor()` — add `Tool.builder()` with name,
   description, and `inputSchema`
2. **Implement it** in `MCPToolExecutor.execute()` — add a `case "your_tool_name"` and
   a private handler method
3. **Update the system prompt** in `SubAgent.buildSystemPrompt()` if you want to guide
   Claude on when to call it

### Add a New Subagent Role

1. Add the value to `AgentRole` enum
2. Add a `case YOUR_ROLE -> List.of(...)` in `MCPToolRegistry.buildToolsFor()`
3. Add a `case YOUR_ROLE -> "..."` in `SubAgent.buildSystemPrompt()`
4. Add routing logic in `CoordinatorAgent.classify()`
5. Add the new `SubAgent` instance to `CoordinatorAgent` constructor

### Replace Stubs with Real MCP Servers

`MCPToolExecutor` is the only class that needs to change. For each tool handler:

```java
// BEFORE (Excel mock):
private String lookupOrder(JsonNode input) throws Exception {
    String orderId = input.path("order_id").asText("").toUpperCase();
    return mapper.writeValueAsString(ordersById.get(orderId));
}

// AFTER (real MCP server):
private String lookupOrder(JsonNode input) throws Exception {
    return httpClient.post("https://orders-mcp.internal/lookup", input.toString());
}
```

Everything else — the tool-use loop, the message history management, the escalation
logic — is completely unchanged.

---

## CI/CD Integration

This project ships with three GitHub Actions workflows and a Python script that
demonstrate how to integrate Claude into automated pipelines. See
[`docs/cicd-integration-guide.md`](docs/cicd-integration-guide.md) for the full
architectural comparison of all integration options.

### The Three Patterns (exam summary)

| Pattern | How | When |
|---|---|---|
| **A — Direct API** | Python/Java SDK in a workflow step | Custom parsing, JSON output, any platform |
| **B — Claude Code CLI** | `claude --print "..."` in a shell step | Agentic tasks, file reads, MCP tools |
| **C — claude-code-action** | `uses: anthropics/claude-code-action@beta` | Zero-config PR review |

### Workflows in this project

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | Push / PR | Compiles + tests with Maven AND Gradle (matrix) |
| `claude-pr-review.yml` | PR open/update | Calls Claude API, posts structured review as PR comment |
| `claude-test-gen.yml` | PR with Java changes | Claude suggests JUnit 5 tests → GitHub Actions summary tab |

### Enabling the workflows

1. Push this repo to GitHub
2. Go to **Settings → Secrets and variables → Actions**
3. Add secret: `ANTHROPIC_API_KEY` = `sk-ant-api03-...`
4. Open a PR — the review and test-gen workflows trigger automatically

### Key exam concepts in `scripts/claude_review.py`

```python
# Concept 8 — Prompt caching: system prompt cached across calls in one pipeline run
system=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}]

# Concept 9 — Structured output: Claude returns JSON → parsed for severity routing
result = json.loads(response.content[0].text)

# Concept 9 cont — Exit-code merge gate: CRITICAL findings fail the CI step
if any(f["severity"] == "CRITICAL" for f in result["findings"]):
    sys.exit(1)   # GitHub Actions marks the step failed → blocks merge
```

---

## Troubleshooting

### `Fatal error: ANTHROPIC_API_KEY not set`
```bash
export ANTHROPIC_API_KEY=sk-ant-api03-...
# Or in IntelliJ: Run → Edit Configurations → Environment variables
```

### `Fatal error: Failed to load mock-data.xlsx`
The Excel file was not compiled into the classpath. Run:
```bash
mvn compile
# This copies src/main/resources/ into target/classes/
```
If the file does not exist yet:
```bash
java -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null):target/classes" \
     com.example.support.util.MockDataGenerator
mvn compile
```

### `400 invalid_request_error: tool_use block missing`
You removed the assistant turn from `messages[]` before the next API call. The full
`response.content()` (including `ToolUseBlock`) must be appended as an ASSISTANT turn.
See `SubAgent.java` step 2c comments.

### `400 invalid_request_error: tool_result missing for tool_use_id`
Every tool use ID in the assistant turn needs a matching `tool_result` in the next user
turn. See `SubAgent.java` step 2d comments.

### No DEBUG logs visible
Ensure `src/main/resources/logback.xml` was compiled:
```bash
mvn compile
# logback.xml must appear in target/classes/
```

---

## API Reference Cheatsheet

| Task | SDK Call |
|---|---|
| Create client | `AnthropicOkHttpClient.fromEnv()` |
| Send message | `client.messages().create(MessageCreateParams)` |
| Check stop reason | `response.stopReason().orElse(null) == StopReason.END_TURN` |
| Get text from response | `block.isText()` + `block.text().get().text()` |
| Get tool use from response | `block.isToolUse()` + `block.toolUse().get()` |
| Tool use: get ID | `toolUse.id()` |
| Tool use: get name | `toolUse.name()` |
| Tool use: get input JSON | `toolUse._input().toString()` |
| Wrap Tool → ToolUnion | `ToolUnion.ofTool(tool)` |
| Build tool result param | `ToolResultBlockParam.builder().toolUseId(id).content(...).build()` |
| Build content block param | `ContentBlockParam.ofToolResult(toolResultParam)` |
| Convert response to params | `contentBlock.toParam()` |
| Wrap string as content | `MessageParam.Content.ofString("...")` |
| Wrap block list as content | `MessageParam.Content.ofBlockParams(List<ContentBlockParam>)` |
| Wrap JsonNode as JsonValue | `JsonValue.fromJsonNode(jsonNode)` |
| Build tool input schema | `Tool.InputSchema.builder().type(JsonValue.from("object")).properties(...).required(List.of(...)).build()` |
