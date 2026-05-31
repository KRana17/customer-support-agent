Explain the Claude architect exam concept: $ARGUMENTS

Map the input to one of these 9 concepts and its primary file:
- "tool-use" | "tool-use-loop" | "stop-reason"  → SubAgent.java (the handle() method and tool-use loop)
- "multi-agent" | "coordinator" | "orchestration" → CoordinatorAgent.java
- "parallel" | "virtual-threads" | "fan-out"      → CoordinatorAgent.java (CompletableFuture section)
- "escalation" | "handoff"                        → EscalationEvaluator.java + HandoffContext.java
- "tool-schema" | "json-schema" | "input-schema"  → MCPToolRegistry.java
- "stateless" | "message-history" | "history"     → SubAgent.java (the messages list in the loop)
- "cicd" | "github-actions" | "automation"        → .github/workflows/claude-pr-review.yml
- "caching" | "prompt-caching" | "cache-control"  → scripts/claude_review.py
- "structured-output" | "json-output" | "exit-code" → scripts/claude_review.py

Read the primary file before answering. Then structure your response exactly as:

**Definition** (1 sentence — exam-ready, precise)

**Why it matters for the exam** (2-3 sentences on what architects are tested on)

**Code walkthrough** (point to 2-3 specific lines or methods in the file, explain what each does and why)

**Common mistake** (the single most frequent error developers make with this concept)

**Exam tip** (one concrete thing to memorize — a method name, a rule, a threshold)

Keep the entire response under 350 words.
