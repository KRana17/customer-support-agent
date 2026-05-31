Run an interactive 10-question mock exam covering all 9 Claude architect exam concepts demonstrated in this project.

Read the following files before generating questions so your questions reference real code:
- src/main/java/com/example/support/agent/SubAgent.java
- src/main/java/com/example/support/mcp/MCPToolRegistry.java
- scripts/claude_review.py

Generate 10 questions spanning all 9 concepts (concepts 1 and 4 get 2 questions each as they are the most exam-heavy). Use this mix:
- 4 multiple choice (4 options, one correct)
- 2 true/false
- 2 short answer
- 2 code-reading (paste a real snippet from this project, ask what it does or what the bug is)

Present ONE question at a time. After the user answers:
- Reveal ✅ Correct or ❌ Incorrect
- Give a 1-2 sentence explanation citing the specific file and concept
- Then immediately present the next question

After all 10 questions, show:
```
─── Exam Results ───────────────────────────────
Score: X / 10
Passed: [✅ if ≥ 7, ❌ if < 7]

Strong areas:   [concepts scored correctly]
Review these:   [concepts scored incorrectly]

Recommended next steps:
  /explain [weakest concept]
  /quiz [second weakest concept]
```

Start immediately with Question 1 — do not ask for confirmation.
