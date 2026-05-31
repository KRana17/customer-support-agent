Read the file README.md to get the authoritative concept list, then produce a compact single-page reference table of all 9 exam concepts in this project.

For each concept output:
- Concept number and name
- The single most important source file to read
- The test class that verifies it (or "—" if none)
- One "Exam Tip" — the single most likely exam question about this concept, phrased as a question

Format as a markdown table followed by a "Quick Reference" section listing the 5 non-obvious SDK type corrections (things that look right but are wrong in SDK 2.32.0 — e.g. `ContentBlock.ToolUse` does not exist, use `block.isToolUse()`).

Keep the entire output to ~60 lines — this is meant to fit on one screen.
