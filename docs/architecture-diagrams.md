# Architecture Diagrams

All diagrams use [Mermaid](https://mermaid.js.org/) — rendered natively on GitHub,
GitLab, and in VS Code / IntelliJ with the Mermaid plugin.

---

## 1. System Overview (Component Diagram)

High-level view of all components and their dependencies.

```mermaid
graph TB
    subgraph Entry["Entry Point"]
        Runner["SupportAgentRunner<br/>(main)"]
    end

    subgraph Coordinator["Coordinator Layer"]
        CA["CoordinatorAgent<br/>classify → fan-out → fan-in"]
    end

    subgraph Agents["Subagent Layer (parallel virtual threads)"]
        FAQ["FAQ SubAgent<br/>tool-use loop"]
        ORDER["ORDER SubAgent<br/>tool-use loop"]
        TECH["TECHNICAL SubAgent<br/>tool-use loop"]
    end

    subgraph Escalation["Escalation Layer"]
        EE["EscalationEvaluator<br/>confidence · sentiment · complexity"]
        ED["EscalationDecision<br/>Resolved | Escalated"]
    end

    subgraph MCP["MCP Tool Layer"]
        REG["MCPToolRegistry<br/>role-scoped tool definitions"]
        EXEC["MCPToolExecutor<br/>tool dispatch"]
        XLS["mock-data.xlsx<br/>Orders · KnowledgeBase · Diagnostics"]
    end

    subgraph Anthropic["Anthropic API"]
        API["claude-sonnet-4-5<br/>Messages API"]
    end

    Runner -->|"CustomerInquiry"| CA
    CA -->|"fan-out"| FAQ & ORDER & TECH
    FAQ & ORDER & TECH -->|"SubAgentResult"| EE
    EE --> ED
    ED -->|"Resolved/Escalated"| Runner

    FAQ & ORDER & TECH <-->|"tool calls"| EXEC
    REG -->|"List&lt;Tool&gt;"| FAQ & ORDER & TECH
    EXEC -->|"loads at startup"| XLS
    FAQ & ORDER & TECH <-->|"Messages API"| API
```

---

## 2. Sequence Diagram — Scenario 1: Simple FAQ

One agent, one tool call, resolved automatically.

```mermaid
sequenceDiagram
    actor Customer
    participant Runner as SupportAgentRunner
    participant Coord as CoordinatorAgent
    participant FAQ as FAQ SubAgent
    participant Exec as MCPToolExecutor
    participant API as Anthropic API

    Customer->>Runner: "What is your return policy?"
    Runner->>Coord: handle(CustomerInquiry)

    Coord->>Coord: classify() → [faqAgent]

    Coord->>FAQ: supplyAsync(handle(inquiry))

    FAQ->>API: create(messages, tools=[search_knowledge_base])
    API-->>FAQ: stop_reason=tool_use<br/>tool: search_knowledge_base(query="return policy")

    FAQ->>Exec: execute("search_knowledge_base", input)
    Exec-->>FAQ: {"answer": "Items can be returned within 30 days..."}

    FAQ->>API: create(messages + tool_result)
    API-->>FAQ: stop_reason=end_turn<br/>"Our return policy allows 30-day returns..."

    FAQ-->>Coord: SubAgentResult(confidence=0.88)

    Coord->>Coord: EscalationEvaluator<br/>minConfidence=0.88 ≥ 0.75 ✅<br/>frustration=false ✅

    Coord-->>Runner: EscalationDecision.Resolved(response)
    Runner-->>Customer: ✅ "Our return policy allows 30-day returns..."
```

---

## 3. Sequence Diagram — Scenario 2: Order + Refund (Multi-Round Tool-Use Loop)

ORDER agent runs two tool calls in sequence before giving a final answer.

```mermaid
sequenceDiagram
    actor Customer
    participant Runner as SupportAgentRunner
    participant Coord as CoordinatorAgent
    participant ORDER as ORDER SubAgent
    participant Exec as MCPToolExecutor
    participant API as Anthropic API

    Customer->>Runner: "My order ORD-77291 hasn't arrived. I want a refund."
    Runner->>Coord: handle(CustomerInquiry)
    Coord->>Coord: classify() → [orderAgent]

    Coord->>ORDER: supplyAsync(handle(inquiry))

    Note over ORDER,API: Round 1 — look up the order
    ORDER->>API: create(messages, tools=[lookup_order, initiate_refund])
    API-->>ORDER: stop_reason=tool_use<br/>tool: lookup_order(order_id="ORD-77291")

    ORDER->>Exec: execute("lookup_order", {order_id: "ORD-77291"})
    Exec-->>ORDER: {status: "SHIPPED", refund_eligible: true, ...}

    Note over ORDER,API: Round 2 — customer requested refund AND eligible=true
    ORDER->>API: create(messages + tool_result)
    API-->>ORDER: stop_reason=tool_use<br/>tool: initiate_refund(order_id="ORD-77291")

    ORDER->>Exec: execute("initiate_refund", {order_id: "ORD-77291"})
    Exec-->>ORDER: {refund_id: "REF-...", status: "INITIATED", estimated_credit_days: 3}

    Note over ORDER,API: Round 3 — formulate final answer
    ORDER->>API: create(messages + tool_result)
    API-->>ORDER: stop_reason=end_turn<br/>"Your refund has been initiated..."

    ORDER-->>Coord: SubAgentResult(confidence=0.88)
    Coord->>Coord: EscalationEvaluator → Resolved ✅
    Coord-->>Runner: EscalationDecision.Resolved
    Runner-->>Customer: ✅ "Your refund REF-... will appear in 3 business days."
```

---

## 4. Sequence Diagram — Scenario 3: Parallel Agents + Escalation

Two agents run in parallel; frustration signal triggers escalation.

```mermaid
sequenceDiagram
    actor Customer
    participant Runner as SupportAgentRunner
    participant Coord as CoordinatorAgent
    participant ORDER as ORDER SubAgent
    participant TECH as TECHNICAL SubAgent
    participant Exec as MCPToolExecutor
    participant API as Anthropic API
    participant Human as Human Agent Queue

    Customer->>Runner: "This is UNACCEPTABLE! Order wrong AND app crashing!"
    Runner->>Coord: handle(CustomerInquiry)
    Coord->>Coord: classify() → [orderAgent, technicalAgent]

    Note over Coord,TECH: Fan-out: both agents launch simultaneously (virtual threads)
    par ORDER agent
        Coord->>ORDER: supplyAsync(handle)
        ORDER->>API: create(tools=[lookup_order, initiate_refund])
        API-->>ORDER: tool_use: lookup_order(ORD-55100)
        ORDER->>Exec: execute("lookup_order")
        Exec-->>ORDER: {status: DELIVERED, refund_eligible: false}
        ORDER->>API: create(+ tool_result)
        API-->>ORDER: end_turn — "Order delivered; refund not eligible"
        ORDER-->>Coord: SubAgentResult(confidence=0.75, issues=0)
    and TECHNICAL agent
        Coord->>TECH: supplyAsync(handle)
        TECH->>API: create(tools=[run_diagnostic, create_ticket])
        API-->>TECH: tool_use: run_diagnostic(user_id=CUST-003)
        TECH->>Exec: execute("run_diagnostic")
        Exec-->>TECH: {connectivity: FAIL, known_incidents: true}
        TECH->>API: create(+ tool_result)
        API-->>TECH: tool_use: create_ticket(priority=HIGH)
        TECH->>Exec: execute("create_ticket")
        Exec-->>TECH: {ticket_id: TKT-..., status: OPEN}
        TECH->>API: create(+ tool_result)
        API-->>TECH: end_turn — "Ticket TKT-... created"
        TECH-->>Coord: SubAgentResult(confidence=0.88, issues=0)
    end

    Note over Coord: Fan-in: allOf().join() — wait for both results

    Coord->>Coord: EscalationEvaluator<br/>"unacceptable" → frustration=true 🚨<br/>→ Priority.HIGH

    Coord-->>Runner: EscalationDecision.Escalated(HandoffContext)
    Runner-->>Human: 🚨 Priority=HIGH<br/>Summaries from both agents<br/>recommendedNextSteps
    Runner-->>Customer: (no automated reply — human takes over)
```

---

## 5. Flowchart — Tool-Use Loop State Machine

The exact states inside `SubAgent.handle()`.

```mermaid
flowchart TD
    START([Start: CustomerInquiry received]) --> INIT
    INIT["Build initial messages list\n[USER: customer inquiry]"] --> CALL

    CALL["API call\nclient.messages.create(params)"] --> CHECK

    CHECK{stop_reason?}

    CHECK -->|end_turn| EXTRACT
    CHECK -->|tool_use| TOOLS
    CHECK -->|other| LOWCONF

    EXTRACT["Extract text from\nresponse.content()\n(isText() + text().get().text())"]
    --> CONFIDENCE["Estimate confidence\nlength + hedging heuristic"]
    --> RETURN_OK([Return SubAgentResult\nconfidence ≥ 0.40])

    TOOLS["For each ContentBlock\nwhere isToolUse() == true"]
    --> EXECUTE["toolExecutor.execute(\n  toolUse.name(),\n  toolUse._input().toString()\n)"]
    --> APPEND_A["Append ASSISTANT turn\n(full assistantContent.toParam())"]
    --> APPEND_U["Append USER turn\n(all ToolResultBlockParam wrapped\nas ContentBlockParam.ofToolResult)"]
    --> INCR{round < MAX_TOOL_ROUNDS?}

    INCR -->|yes| CALL
    INCR -->|no| LOWCONF

    LOWCONF(["Return SubAgentResult.lowConfidence()\nconfidence = 0.30\n→ triggers escalation"])

    style START fill:#4CAF50,color:#fff
    style RETURN_OK fill:#4CAF50,color:#fff
    style LOWCONF fill:#f44336,color:#fff
    style CHECK fill:#2196F3,color:#fff
    style INCR fill:#2196F3,color:#fff
```

---

## 6. Flowchart — Escalation Decision Logic

How `EscalationEvaluator` decides between Resolved and Escalated.

```mermaid
flowchart TD
    IN([SubAgentResult list\n+ CustomerInquiry]) --> CONF

    CONF["Compute minConfidence\n= min of all subagent confidence scores"]
    --> FRUST["Detect frustration\nkeyword scan on inquiry.text()"]
    --> COMPLEX["Count complexity\n= agents with issuesDetected > 0"]
    --> GATE

    GATE{Any signal true?}

    GATE -->|"minConfidence < 0.75\nOR frustration = true\nOR issueCount ≥ 2"| ESC_PATH
    GATE -->|all signals false| RESOLVE

    ESC_PATH["Build HandoffContext\n· all SubAgentResult summaries\n· recommendedNextSteps\n· priority (HIGH/MEDIUM/LOW)"]
    --> PRIO

    PRIO{Priority level}
    PRIO -->|"frustration = true\nOR confidence < 0.40"| HIGH["Priority.HIGH\n→ immediate queue"]
    PRIO -->|"confidence < 0.60"| MED["Priority.MEDIUM\n→ SLA queue"]
    PRIO -->|else| LOW["Priority.LOW\n→ standard queue"]

    HIGH & MED & LOW --> ESCALATED([EscalationDecision.Escalated])

    RESOLVE["synthesize()\ncombine subagent answers\ninto single response"]
    --> RESOLVED([EscalationDecision.Resolved])

    style ESCALATED fill:#f44336,color:#fff
    style RESOLVED fill:#4CAF50,color:#fff
    style GATE fill:#2196F3,color:#fff
    style PRIO fill:#2196F3,color:#fff
```

---

## 7. Class Diagram — Model Layer

Relationships between all domain objects.

```mermaid
classDiagram
    class CustomerInquiry {
        +String customerId
        +String orderId
        +String text
        +InquiryType type
    }
    class InquiryType {
        <<enumeration>>
        FAQ
        ORDER
        TECHNICAL
        UNKNOWN
    }
    class AgentRole {
        <<enumeration>>
        FAQ
        ORDER
        TECHNICAL
    }
    class SubAgentResult {
        +AgentRole agentRole
        +String answer
        +double confidence
        +int issuesDetected
        +lowConfidence(AgentRole, String)$
    }
    class EscalationDecision {
        <<sealed interface>>
        +resolve(String)$
        +escalate(HandoffContext)$
    }
    class Resolved {
        +String response
    }
    class Escalated {
        +HandoffContext context
    }
    class HandoffContext {
        +CustomerInquiry inquiry
        +List~SubAgentResult~ subAgentSummaries
        +String recommendedNextSteps
        +Priority priority
        +of(...)$
    }
    class Priority {
        <<enumeration>>
        HIGH
        MEDIUM
        LOW
    }

    CustomerInquiry --> InquiryType
    SubAgentResult --> AgentRole
    EscalationDecision <|.. Resolved : permits
    EscalationDecision <|.. Escalated : permits
    Escalated --> HandoffContext
    HandoffContext --> CustomerInquiry
    HandoffContext --> SubAgentResult
    HandoffContext --> Priority
```

---

## 8. Sequence Diagram — CI/CD Pipeline (Claude PR Review)

What happens when a developer opens a pull request.

```mermaid
sequenceDiagram
    actor Dev as Developer
    participant GH as GitHub
    participant CI as ci.yml<br/>(Build & Test)
    participant Review as claude-pr-review.yml
    participant Script as claude_review.py
    participant API as Anthropic API
    participant GHAPI as GitHub REST API

    Dev->>GH: git push → open PR

    par Build gate runs first
        GH->>CI: trigger on pull_request
        CI->>CI: actions/setup-java (Java 21)
        CI->>CI: mvn clean compile test
        CI-->>GH: ✅ Build passed
    and Review workflow triggered simultaneously
        GH->>Review: trigger on pull_request
        Review->>Review: actions/checkout (fetch-depth=0)
        Review->>Review: git diff origin/main...HEAD -- *.java
        Review->>Review: diff_size > 100? ✅
        Review->>Script: python claude_review.py --diff pr_diff.txt --post-comment
        Note over Script,API: Prompt caching: system prompt cached<br/>cache_control: ephemeral (5-min TTL)
        Script->>API: messages.create(system=[cached], user=diff)
        API-->>Script: JSON {summary, findings[], score, approved}
        Script->>Script: json.loads() → validate structure
        Script->>GHAPI: POST /repos/{repo}/issues/{pr}/comments
        GHAPI-->>GH: Comment posted ✅
        Script->>Script: CRITICAL findings? → sys.exit(1)
        Review-->>GH: ✅ or ❌ (blocks merge if CRITICAL)
    end

    GH-->>Dev: PR shows build status + Claude review comment
```

---

## 9. Flowchart — Multi-Agent Fan-Out / Fan-In

How `CoordinatorAgent` orchestrates parallel execution.

```mermaid
flowchart LR
    INQ([CustomerInquiry]) --> CLS

    CLS["classify(inquiry)\nkeyword routing"]

    CLS -->|"contains 'order'\nor orderId != null"| O["ORDER agent"]
    CLS -->|"contains 'crash'\nor 'broken'"| T["TECHNICAL agent"]
    CLS -->|"contains 'how'\nor 'policy'"| F["FAQ agent"]

    subgraph FanOut["Fan-Out (CompletableFuture.supplyAsync × N)"]
        O
        T
        F
    end

    subgraph VT["Java 21 Virtual Threads"]
        O -->|"blocks on HTTP"| VT1[" "]
        T -->|"blocks on HTTP"| VT2[" "]
        F -->|"blocks on HTTP"| VT3[" "]
    end

    VT1 --> OR["SubAgentResult\n(ORDER)"]
    VT2 --> TR["SubAgentResult\n(TECHNICAL)"]
    VT3 --> FR["SubAgentResult\n(FAQ)"]

    subgraph FanIn["Fan-In (CompletableFuture.allOf().join())"]
        OR & TR & FR --> COLLECT["List&lt;SubAgentResult&gt;"]
    end

    COLLECT --> EE["EscalationEvaluator"]
    EE -->|resolved| RES([Resolved ✅])
    EE -->|escalated| ESC([Escalated 🚨])
```

---

## 10. Flowchart — Prompt Caching Strategy (CI Pipeline)

How prompt caching reduces token cost across multiple Claude calls in one pipeline run.

```mermaid
flowchart TD
    subgraph Pipeline["GitHub Actions Run"]
        WF1["claude-pr-review.yml\nStep: Run Claude review"]
        WF2["claude-test-gen.yml\nStep: Generate test suggestions"]
    end

    subgraph Script1["claude_review.py (review mode)"]
        SYS1["system prompt\n~800 tokens\ncache_control: ephemeral"]
        USR1["user: diff content\n(varies per PR)"]
    end

    subgraph Script2["claude_review.py (test-gen mode)"]
        SYS2["system prompt\n~600 tokens\ncache_control: ephemeral"]
        USR2["user: file contents\n(varies per PR)"]
    end

    subgraph Cache["Anthropic Cache (5-min TTL)"]
        C1["Cached: REVIEW_SYSTEM_PROMPT\nbilled at 0.1× on hit"]
        C2["Cached: TEST_GEN_SYSTEM_PROMPT\nbilled at 0.1× on hit"]
    end

    WF1 --> SYS1
    WF2 --> SYS2

    SYS1 -->|"first call: WRITE\nbilled 1.25×"| C1
    SYS2 -->|"first call: WRITE\nbilled 1.25×"| C2

    SYS1 -->|"repeat calls: READ\nbilled 0.10×"| C1
    SYS2 -->|"repeat calls: READ\nbilled 0.10×"| C2

    USR1 -->|"always billed 1×\n(unique each call)"| API[(Anthropic API)]
    USR2 -->|"always billed 1×\n(unique each call)"| API
    C1 --> API
    C2 --> API
```
