Trace the execution path through the customer support agent system for this customer message: $ARGUMENTS

Do NOT run any code or call any APIs. Reason purely from reading the source files.

Steps:
1. Read CoordinatorAgent.java — specifically the classify() method — to determine which agents are selected based on the keywords in the message.
2. Read SubAgent.java — the handle() method and buildSystemPrompt() — to determine what tools each selected agent would call, in what order, based on its system prompt rules.
3. Read MCPToolExecutor.java to show what data each tool would return (use the mock-data.xlsx seed data: ORD-77291=SHIPPED/refund_eligible=true, ORD-55100=DELIVERED/refund_eligible=false, CUST-003=FAIL/incidents=true).
4. Read EscalationEvaluator.java to predict the escalation decision: check confidence signal (< 0.75?), frustration signal (keyword match?), complexity signal (≥ 2 agents with issues?).

Format your trace exactly like this:

```
Input: "[the customer message]"

─── classify() ────────────────────────────────────
Matched keywords: [list]
Selected agents:  [FAQ | ORDER | TECHNICAL] (parallel)

─── [AGENT] SubAgent ──────────────────────────────
System prompt rule: "[relevant rule from buildSystemPrompt()]"
  Round 1 → [tool_name]([args]) → [mock result summary]
  Round 2 → [tool_name or end_turn]
  → Answer: "[brief summary]"
  → confidence: [estimated value based on answer length/hedging]

─── EscalationEvaluator ───────────────────────────
minConfidence = [value]  (threshold: 0.75)  [PASS/FAIL]
frustration   = [true/false] (keyword: "[matched word if any]")  [PASS/FAIL]
complexity    = [count] agents with issues  (threshold: 2)  [PASS/FAIL]

Decision: [✅ RESOLVED | 🚨 ESCALATED (Priority: HIGH/MEDIUM/LOW)]
Reason:   [which signal(s) triggered]
```
