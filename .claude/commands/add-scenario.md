Add a new demo scenario to SupportAgentRunner.java based on this description: $ARGUMENTS

Steps:
1. Read src/main/java/com/example/support/runner/SupportAgentRunner.java to understand the existing 3 scenarios and their structure.
2. From the description, determine:
   - customerId: use "CUST-004" (or next available if already used)
   - orderId: include if the scenario involves orders, otherwise null
   - inquiryType: FAQ | ORDER | TECHNICAL | UNKNOWN
   - message text: realistic customer phrasing that clearly triggers the right agents
3. Decide which exam concept(s) this scenario best demonstrates (e.g. "shows MAX_TOOL_ROUNDS cap", "shows complexity escalation signal", "shows FAQ single-tool resolution").
4. Edit SupportAgentRunner.java to add the new runDemo() call after the existing Scenario 3 block. Include a comment like:
   ```java
   // ── Demo 4: [short name] — demonstrates [exam concept] ─────────────────────
   ```
5. If the scenario references an order (orderId != null) or a customer diagnostic, remind the user to add matching rows to src/main/resources/mock-data.xlsx — show exactly what columns to fill in.

After editing, show a summary:
- What was added to SupportAgentRunner.java
- Which agents will be triggered and why
- Predicted outcome (Resolved or Escalated) based on EscalationEvaluator logic
- Any mock-data.xlsx rows needed
