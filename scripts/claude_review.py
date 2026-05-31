#!/usr/bin/env python3
"""
Claude CI/CD Code Reviewer & Test Generator
============================================
Called by GitHub Actions workflows to:
  - Review a Java diff and post findings as a PR comment  (--mode review)
  - Suggest JUnit 5 tests for changed files               (--mode test-gen)

EXAM CONCEPTS DEMONSTRATED IN THIS FILE:
─────────────────────────────────────────
  1. Single-turn API call for automation   — no tool-use loop needed for batch tasks
  2. Prompt caching (cache_control)        — reduce token cost for repeated system prompts
  3. Structured JSON output from Claude    — machine-parseable severity findings
  4. System prompt design for specialised tasks
  5. Error handling in automated contexts — never crash the pipeline silently
  6. GitHub REST API integration           — posting comments programmatically

Usage:
  python scripts/claude_review.py --diff /tmp/pr_diff.txt [--post-comment]
  python scripts/claude_review.py --mode test-gen --files /tmp/changed_files.txt
"""

import anthropic
import argparse
import json
import os
import sys
import urllib.request
import urllib.error
from pathlib import Path


# ──────────────────────────────────────────────────────────────────────────────
# EXAM CONCEPT 1 — Client Initialisation
#
# anthropic.Anthropic() reads ANTHROPIC_API_KEY from the environment.
# This is identical in intent to the Java SDK's AnthropicOkHttpClient.fromEnv().
# In CI, the key is injected via GitHub Secrets → env: block in the workflow.
# Never hardcode the key or read it from a file committed to the repo.
# ──────────────────────────────────────────────────────────────────────────────
client = anthropic.Anthropic()


# ──────────────────────────────────────────────────────────────────────────────
# EXAM CONCEPT 2 — System Prompt Design for a Specialised Task
#
# A well-crafted system prompt constrains Claude's behavior:
#   - Domain focus: Java + Anthropic SDK specifics
#   - Output contract: JSON ONLY — no prose outside the JSON object
#   - Schema definition inline: Claude knows exactly what fields to produce
#
# This is the same principle as the SubAgent system prompts in SubAgent.java:
# each agent has a focused, constrained system prompt that prevents drift.
# ──────────────────────────────────────────────────────────────────────────────
REVIEW_SYSTEM_PROMPT = """You are an expert Java code reviewer specialising in:
- Anthropic Java SDK 2.x usage correctness (tool schemas, message params, stop reasons)
- Thread safety and concurrency bugs (especially with CompletableFuture and virtual threads)
- Null pointer risks and missing error handling
- Code clarity and maintainability

Return ONLY valid JSON — no markdown, no prose, no code fences. Your entire response
must be a single JSON object matching this exact schema:

{
  "summary": "<1-2 sentence overview of the changes>",
  "findings": [
    {
      "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
      "file": "<relative/path/File.java>",
      "line_hint": "<approximate line or range, e.g. '45' or '40-50'>",
      "issue": "<concise description of the problem>",
      "suggestion": "<concise fix suggestion>"
    }
  ],
  "overall_score": <integer 0-10>,
  "approved": <true if no CRITICAL/HIGH findings, else false>
}

Severity guide:
  CRITICAL — data loss, security vulnerability, will crash in production
  HIGH     — likely bug, incorrect API usage, race condition
  MEDIUM   — code smell, potential NPE, inefficient pattern
  LOW      — style issue, minor improvement opportunity
  INFO     — observation, not a problem
"""

TEST_GEN_SYSTEM_PROMPT = """You are an expert Java test engineer specialising in JUnit 5
and Mockito. Given a list of changed Java source files, suggest concrete test cases.

Format your response as Markdown (this will be posted to the GitHub Actions summary tab).
Include:
- A brief section per file (## FileName)
- Bullet list of specific test method names with @Test annotations
- One code snippet per file showing the most important test case

Focus on: edge cases, null inputs, concurrent access, tool-use loop scenarios for
Anthropic SDK code. Keep suggestions realistic — no mocking of final classes.
"""


# ──────────────────────────────────────────────────────────────────────────────
# EXAM CONCEPT 3 — Prompt Caching (cache_control: ephemeral)
#
# The system prompt is the same across every API call in a single CI run.
# Marking it with cache_control tells the Anthropic API to cache the tokenised
# version on its servers for 5 minutes (the "ephemeral" TTL).
#
# Cost impact:
#   - First call:  system prompt tokens billed at full rate (cache WRITE)
#   - Subsequent calls: system prompt tokens billed at ~10% of full rate (cache READ)
#
# When useful in CI:
#   - Reviewing many files in a loop: same system prompt, different user content
#   - Multiple Claude calls in one pipeline run (review + test-gen)
#
# Exam tip: cache_control lives on the CONTENT BLOCK, not on the message.
# For a system prompt string, wrap it in a list with type/text/cache_control.
# ──────────────────────────────────────────────────────────────────────────────
def make_cached_system(prompt_text: str) -> list[dict]:
    """Wraps a system prompt string into the cache_control content block format."""
    return [
        {
            "type": "text",
            "text": prompt_text,
            "cache_control": {"type": "ephemeral"},   # ← 5-minute cache TTL
        }
    ]


# ──────────────────────────────────────────────────────────────────────────────
# EXAM CONCEPT 4 — Single-Turn API Call (no tool-use loop)
#
# Code review is a single-turn task: one prompt → one structured response.
# There is no need for a tool-use loop here — Claude does not need to call
# external tools to review a diff.
#
# Contrast with SubAgent.java (agentic loop) vs this file (single-turn batch):
#   - Use tool-use loop when: Claude needs to fetch data to answer (orders, KB, diagnostics)
#   - Use single-turn when: all context is in the prompt (a diff, a file list)
# ──────────────────────────────────────────────────────────────────────────────
def review_diff(diff_text: str) -> dict:
    """
    Sends a Java diff to Claude and returns a structured review as a dict.

    Returns:
        dict with keys: summary, findings, overall_score, approved
    Raises:
        ValueError if Claude returns non-JSON output
        anthropic.APIError on API failures
    """
    response = client.messages.create(
        model="claude-sonnet-4-5",
        max_tokens=2048,
        system=make_cached_system(REVIEW_SYSTEM_PROMPT),   # cached system prompt
        messages=[
            {
                "role": "user",
                "content": (
                    f"Review this Java diff and return your findings as JSON:\n\n"
                    f"```diff\n{diff_text}\n```"
                ),
            }
        ],
    )

    # ── EXAM CONCEPT 5 — Structured Output Parsing ────────────────────────────
    # Claude was instructed (via system prompt) to return JSON only.
    # We parse it here for programmatic use: severity routing, metrics, etc.
    #
    # Defensive pattern for automation:
    #   - Strip any accidental whitespace/newlines
    #   - Catch json.JSONDecodeError and fail explicitly (don't silently swallow)
    raw = response.content[0].text.strip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError as e:
        raise ValueError(
            f"Claude returned non-JSON output (prompt caching may be off, "
            f"or the model added prose). Raw response:\n{raw[:500]}"
        ) from e


def generate_test_suggestions(file_paths: list[str]) -> str:
    """
    Asks Claude to suggest JUnit 5 tests for a list of changed Java files.
    Returns Markdown string (for $GITHUB_STEP_SUMMARY).
    """
    # Read the actual file contents to give Claude concrete code to work with
    file_contents = []
    for path in file_paths:
        p = Path(path.strip())
        if p.exists() and p.suffix == ".java":
            content = p.read_text(encoding="utf-8")
            file_contents.append(f"### {p.name}\n```java\n{content[:3000]}\n```")
            # Cap at 3000 chars per file to stay within token budget

    if not file_contents:
        return "## Claude Test Suggestions\n\nNo readable Java files found in the diff."

    user_content = (
        "Suggest JUnit 5 test cases for these changed Java files:\n\n"
        + "\n\n".join(file_contents)
    )

    response = client.messages.create(
        model="claude-sonnet-4-5",
        max_tokens=2048,
        system=make_cached_system(TEST_GEN_SYSTEM_PROMPT),
        messages=[{"role": "user", "content": user_content}],
    )

    return response.content[0].text


# ──────────────────────────────────────────────────────────────────────────────
# EXAM CONCEPT 6 — GitHub REST API Integration
#
# After Claude produces a review, we post it as a PR comment using the GitHub
# REST API. Key points:
#   - GITHUB_TOKEN is auto-injected by the runner — no manual secret needed
#   - The endpoint is POST /repos/{owner}/{repo}/issues/{pr_number}/comments
#     (PRs are a superset of issues in the GitHub API)
#   - Content-Type: application/json + X-GitHub-Api-Version header are required
# ──────────────────────────────────────────────────────────────────────────────
def post_pr_comment(body: str, repo: str, pr_number: str, token: str) -> None:
    """Posts a markdown comment to a GitHub PR."""
    payload = json.dumps({"body": body}).encode("utf-8")
    url = f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments"

    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )

    try:
        with urllib.request.urlopen(req) as resp:
            result = json.loads(resp.read())
            print(f"✅ PR comment posted: {result.get('html_url', 'unknown URL')}")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"GitHub API returned {e.code}: {error_body}"
        ) from e


def format_review_as_markdown(review: dict) -> str:
    """Renders the structured review dict as a GitHub-flavored markdown comment."""
    score = review.get("overall_score", "?")
    approved = review.get("approved", False)
    verdict = "✅ Approved" if approved else "❌ Changes requested"
    findings = review.get("findings", [])

    severity_emoji = {
        "CRITICAL": "🔴",
        "HIGH": "🟠",
        "MEDIUM": "🟡",
        "LOW": "🔵",
        "INFO": "⚪",
    }

    lines = [
        "## 🤖 Claude Code Review",
        "",
        f"**Summary:** {review.get('summary', 'No summary provided.')}",
        f"**Score:** {score}/10 &nbsp; {verdict}",
        "",
    ]

    if not findings:
        lines.append("_No findings — looks good!_")
    else:
        lines += [
            "| Severity | File | Line | Issue | Suggestion |",
            "|:--------:|------|:----:|-------|------------|",
        ]
        for f in findings:
            sev = f.get("severity", "INFO")
            emoji = severity_emoji.get(sev, "⚪")
            lines.append(
                f"| {emoji} {sev} "
                f"| `{f.get('file', '?')}` "
                f"| {f.get('line_hint', '?')} "
                f"| {f.get('issue', '')} "
                f"| {f.get('suggestion', '')} |"
            )

    lines += [
        "",
        "---",
        "_Generated by [Claude](https://claude.ai) via the Anthropic API. "
        "See `scripts/claude_review.py` for implementation details._",
    ]
    return "\n".join(lines)


# ── CLI entry point ────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Claude-powered code reviewer and test generator for CI/CD"
    )
    parser.add_argument(
        "--mode",
        choices=["review", "test-gen"],
        default="review",
        help="review: analyse a diff | test-gen: suggest tests for changed files",
    )
    parser.add_argument("--diff",  help="Path to the diff file (review mode)")
    parser.add_argument("--files", help="Path to file listing changed Java files (test-gen mode)")
    parser.add_argument(
        "--post-comment",
        action="store_true",
        help="Post the review as a GitHub PR comment (requires GITHUB_TOKEN, REPO, PR_NUMBER env vars)",
    )
    args = parser.parse_args()

    # ── Review mode ────────────────────────────────────────────────────────────
    if args.mode == "review":
        if not args.diff:
            parser.error("--diff is required for review mode")

        diff_text = Path(args.diff).read_text(encoding="utf-8")
        if not diff_text.strip():
            print("No diff content — nothing to review.")
            return

        print("🔍 Sending diff to Claude for review...")
        review = review_diff(diff_text)

        # Always print the JSON (useful for debugging in CI logs)
        print(json.dumps(review, indent=2))

        if args.post_comment:
            token   = os.environ.get("GITHUB_TOKEN")
            repo    = os.environ.get("REPO")
            pr_num  = os.environ.get("PR_NUMBER")

            if not all([token, repo, pr_num]):
                print(
                    "⚠️  --post-comment requires GITHUB_TOKEN, REPO, and PR_NUMBER env vars.",
                    file=sys.stderr,
                )
                sys.exit(1)

            markdown = format_review_as_markdown(review)
            post_pr_comment(markdown, repo=repo, pr_number=pr_num, token=token)

        # ── EXAM CONCEPT — Exit code as a merge gate ───────────────────────────
        # Return a non-zero exit code if any CRITICAL findings exist.
        # GitHub Actions treats non-zero exit codes as step failures,
        # which can block a PR merge when branch protection is configured.
        critical_count = sum(
            1 for f in review.get("findings", []) if f.get("severity") == "CRITICAL"
        )
        if critical_count > 0:
            print(
                f"\n❌ {critical_count} CRITICAL finding(s) — failing the step.",
                file=sys.stderr,
            )
            sys.exit(1)

    # ── Test-gen mode ──────────────────────────────────────────────────────────
    elif args.mode == "test-gen":
        if not args.files:
            parser.error("--files is required for test-gen mode")

        file_list = Path(args.files).read_text(encoding="utf-8").splitlines()
        file_list = [f for f in file_list if f.strip()]

        if not file_list:
            print("## Claude Test Suggestions\n\nNo changed Java files detected.")
            return

        print(f"🧪 Generating test suggestions for {len(file_list)} file(s)...")
        suggestions = generate_test_suggestions(file_list)
        # Output goes to stdout → redirected to $GITHUB_STEP_SUMMARY in the workflow
        print(suggestions)


if __name__ == "__main__":
    main()
