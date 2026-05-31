# Claude in CI/CD Pipelines — Architect Exam Study Guide

> **Audience:** Developers preparing for the Anthropic Claude AI architect certificate exam.
> This guide covers all major patterns for integrating Claude into automated pipelines,
> with trade-offs and exam-relevant code examples for each.

---

## The Three Integration Patterns

There are three distinct ways to run Claude inside a CI/CD pipeline. Each solves the
same core problem (automate a Claude task) but with different trade-offs:

| | Pattern A: Direct API | Pattern B: Claude Code CLI | Pattern C: claude-code-action |
|---|---|---|---|
| **Setup effort** | Medium | Low | Very low |
| **Control over output** | Full | Medium | Low |
| **Tool use support** | Manual (you implement) | Built-in (agent loop) | Built-in |
| **Prompt caching** | Manual (`cache_control`) | Automatic | Automatic |
| **Best for** | Custom pipelines, any language | Agentic CI tasks, file ops | Quick PR review, zero config |
| **Output destination** | Anywhere you code it | stdout / files | PR inline comments |

---

## Pattern A — Direct API Call (this project's implementation)

**File:** `scripts/claude_review.py` + `.github/workflows/claude-pr-review.yml`

Call the Anthropic Python (or Java) SDK directly from a CI script. You control every
aspect of the prompt, the response format, and where the output goes.

### When to use
- You need structured output (JSON) parsed by downstream automation
- You want to post comments via the GitHub API with custom formatting
- You are integrating Claude into a non-GitHub platform (GitLab, Jenkins, CircleCI)
- You need fine-grained cost control with prompt caching

### Auth setup

```yaml
# In .github/workflows/claude-pr-review.yml
env:
  ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
```

`ANTHROPIC_API_KEY` must be added to **Settings → Secrets and variables → Actions**
in your GitHub repo. It is never exposed in workflow logs.

### Minimal working example

```python
import anthropic, json

client = anthropic.Anthropic()   # reads ANTHROPIC_API_KEY from env

response = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    system="You are a code reviewer. Return JSON only.",
    messages=[{"role": "user", "content": "Review this: " + diff_text}]
)

result = json.loads(response.content[0].text)
```

### Exam code: prompt caching

```python
# Wrap the system prompt in a content block with cache_control
# so it is cached across multiple calls in the same pipeline run.
response = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    system=[{
        "type": "text",
        "text": "You are a code reviewer...",
        "cache_control": {"type": "ephemeral"}   # 5-minute TTL
    }],
    messages=[{"role": "user", "content": diff_text}]
)
```

**Exam tip:** `cache_control` lives on the **content block**, not on the message or
the `system` string. When the system prompt is a plain string, it cannot be cached —
you must use the list-of-blocks format shown above.

---

## Pattern B — Claude Code CLI Headless (`claude --print`)

Claude Code (the CLI tool) can run non-interactively with the `--print` flag.
This gives you Claude's **full agentic capabilities** (file reads, MCP tools, bash
execution) from a shell command — no Python/Java SDK code needed.

### When to use
- You want Claude to autonomously read project files (not just a diff)
- You need Claude to run commands as part of its reasoning
- You want to reuse CLAUDE.md project instructions in CI

### GitHub Actions example

```yaml
- name: Claude autonomous review
  env:
    ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
  run: |
    npm install -g @anthropic-ai/claude-code   # install the CLI

    claude --print \
      "Review src/main/java/com/example/support/agent/SubAgent.java \
       for thread safety issues. Be concise." \
      > /tmp/review.txt

    cat /tmp/review.txt >> "$GITHUB_STEP_SUMMARY"
```

### Key flags

| Flag | Purpose |
|---|---|
| `--print` | Non-interactive mode — output to stdout, then exit |
| `--output-format json` | Return structured JSON instead of plain text |
| `--max-turns N` | Cap the number of agentic tool-use rounds |
| `--no-mcp` | Disable MCP servers (faster, more predictable in CI) |
| `-p "prompt"` | Short form of `--print` |

### Exam note: CLAUDE.md in CI

When `claude --print` runs in CI, it still reads `CLAUDE.md` from the project root
if one exists. This means your project's instructions, conventions, and tool
restrictions apply to the CI run automatically — no duplication needed.

---

## Pattern C — `anthropics/claude-code-action`

The official pre-built GitHub Action from Anthropic. Zero configuration for basic
PR reviews — just add the step and set the secret.

### When to use
- You want PR review without writing any code
- You want inline PR review comments (not just a summary)
- You are prototyping before deciding whether to invest in a custom integration

### Example

```yaml
name: Claude Review
on:
  pull_request:
    types: [opened, synchronize]

jobs:
  review:
    runs-on: ubuntu-latest
    permissions:
      pull-requests: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: anthropics/claude-code-action@beta
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
```

### Exam note: less control, more convenience

`claude-code-action` handles auth, checkout, diff computation, and comment posting
automatically. The trade-off is that you cannot customise the system prompt, output
format, or post-processing logic. For exam scenarios testing architectural judgment,
understand *why* you might choose the direct API (Pattern A) instead:
- You need CRITICAL findings to block a merge (non-zero exit code)
- You need to route HIGH findings to a Slack channel
- You need JSON for a custom dashboard or metrics system

---

## Prompt Caching in CI — Deep Dive

### What gets cached

The Anthropic API caches at the **content block** level. You opt in per block
using `cache_control: {type: "ephemeral"}`. The cache TTL is 5 minutes.

```python
# Only the system prompt is cached here.
# The user message (the diff) changes every call, so caching it is pointless.
system=[{
    "type": "text",
    "text": LONG_SYSTEM_PROMPT,       # 500+ tokens — worth caching
    "cache_control": {"type": "ephemeral"}
}]
```

### Cost model

| Token type | Normal price | Cache write | Cache read |
|---|---|---|---|
| Input tokens | 1× | 1.25× | 0.1× |
| Output tokens | unchanged | unchanged | unchanged |

A 1,000-token system prompt called 10 times in one pipeline:
- Without caching: 10,000 input tokens billed
- With caching: 1,250 (write) + 900 (9 reads × 0.1×) = 2,150 tokens billed
- **Saving: 78%**

### Exam tip: minimum cacheable size

The API only honours `cache_control` on prompts above a minimum token threshold
(currently ~1,024 tokens for Sonnet models). Short system prompts are not cached
even if you add the `cache_control` block — but it is safe to include the block anyway.

---

## Structured Outputs for Automation

Instructing Claude to return JSON-only is a core pattern for CI pipelines where
the output must be parsed by downstream code.

### The contract pattern

1. **System prompt**: define the JSON schema inline and say "return ONLY JSON"
2. **Response parsing**: `json.loads(response.content[0].text.strip())`
3. **Validation**: check required keys are present before acting on the data
4. **Exit codes**: use non-zero exit to signal failures to the CI runner

```python
# System prompt schema definition (excerpt from claude_review.py)
"""
Return ONLY valid JSON matching this exact schema:
{
  "summary": "...",
  "findings": [{"severity": "CRITICAL|HIGH|...", ...}],
  "overall_score": 0-10,
  "approved": true|false
}
"""

# Downstream routing based on findings
critical_count = sum(1 for f in review["findings"] if f["severity"] == "CRITICAL")
if critical_count > 0:
    sys.exit(1)   # ← fails the GitHub Actions step → blocks merge
```

---

## Security Best Practices

### Secrets management

```yaml
# ✅ Correct — key read from GitHub Secrets
env:
  ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}

# ❌ Never do this
env:
  ANTHROPIC_API_KEY: sk-ant-api03-...   # exposed in workflow file
```

### Least-privilege permissions

Always declare the minimum permissions your job needs:

```yaml
permissions:
  pull-requests: write   # to post comments
  contents: read         # to checkout the repo
  # Everything else is implicitly denied
```

If you omit the `permissions:` block, GitHub uses the repo's default — often
`write` for everything, which is unnecessarily broad.

### Fork PRs and secret access

A critical security boundary:

| Trigger | Fork PR access to secrets? |
|---|---|
| `pull_request` | ❌ No — secrets are NOT available for fork PRs |
| `pull_request_target` | ✅ Yes — but the workflow runs in the BASE repo context |

**Exam tip:** `pull_request_target` has write access and can access secrets, but it
executes code from the HEAD of the PR — including code from an untrusted fork. Never
run untrusted code (like `mvn exec:java`) in a `pull_request_target` step. Use
`pull_request` for most cases; add an explicit check if you need fork PRs to get reviews:

```yaml
on:
  pull_request_target:
    types: [opened, synchronize]

jobs:
  review:
    # Only run for PRs from trusted team members or internal branches
    if: github.event.pull_request.head.repo.full_name == github.repository
```

### Validate Claude's output

Claude can occasionally return non-JSON despite the instruction. Always validate:

```python
try:
    result = json.loads(response.content[0].text.strip())
except json.JSONDecodeError:
    # Log the raw output for debugging, then fail gracefully
    print(f"ERROR: Claude returned non-JSON:\n{raw[:200]}", file=sys.stderr)
    sys.exit(1)   # fail the step rather than silently skip the review
```

---

## GitHub Actions Mechanics — Exam Cheatsheet

| Concept | Syntax | Purpose |
|---|---|---|
| Conditional step | `if: steps.id.outputs.key > 0` | Skip expensive steps when not needed |
| Step output | `echo "key=val" >> "$GITHUB_OUTPUT"` | Pass data between steps |
| Step summary | `echo "..." >> "$GITHUB_STEP_SUMMARY"` | Post markdown to Actions summary tab |
| Cancel in progress | `concurrency: { cancel-in-progress: true }` | Stop stale runs on new commits |
| Matrix builds | `strategy: { matrix: { tool: [maven, gradle] } }` | Run the same job with multiple configs |
| Cache dependencies | `cache: maven` in `setup-java` | Avoid re-downloading `.m2` every run |
| Auto-provisioned token | `${{ secrets.GITHUB_TOKEN }}` | GitHub injects this — no user setup needed |
| Path filter | `on: pull_request: paths: ['src/**']` | Only trigger when relevant files change |
| Fetch full history | `actions/checkout@v4 with: { fetch-depth: 0 }` | Required for `git diff` against base branch |

---

## Further Reading

- `scripts/claude_review.py` — full annotated implementation of Pattern A
- `.github/workflows/claude-pr-review.yml` — the PR review workflow
- `.github/workflows/claude-test-gen.yml` — test generation with `$GITHUB_STEP_SUMMARY`
- `.github/workflows/ci.yml` — build gate before Claude runs
- Anthropic docs: Prompt caching, Structured outputs, Claude Code headless mode
