#!/usr/bin/env python3
"""
find_ai_java_repo.py

Searches GitHub for Java repositories that are good Structure202 analysis
candidates: large codebase, active development, and evidence of AI-assisted
commits (Claude co-author trailers).

Usage:
    python find_ai_java_repo.py [--token TOKEN] [--top N]

Requires: requests  (pip install requests)
"""

import argparse
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

try:
    import requests
except ImportError:
    print("ERROR: 'requests' not installed. Run: pip install requests", file=sys.stderr)
    sys.exit(1)

# ── Constants ────────────────────────────────────────────────────────────────

GITHUB_API = "https://api.github.com"

# Patterns that indicate Claude/AI co-authorship in commit messages
AI_COAUTHOR_PATTERNS = [
    "co-authored-by: claude",
    "co-authored-by: github-actions[bot]",
    "generated with claude",
    "generated with [claude",
    "claude code",
    "anthropic",
]

# Initial repo search query: large, active Java projects
SEARCH_QUERY = (
    "language:Java "
    "stars:>500 "
    "size:>50000"   # >50 MB unpacked — guarantees a substantial class count
)

COMMITS_PER_REPO = 30     # how many recent commits to scan per repo
MAX_REPOS_TO_SCAN = 40    # how many search results to examine in detail
RESULTS_PER_PAGE  = 40    # GitHub search page size


# ── Data model ───────────────────────────────────────────────────────────────

@dataclass
class RepoCandidate:
    full_name:      str
    html_url:       str
    description:    str
    stars:          int
    size_kb:        int
    open_issues:    int
    pushed_at:      str
    ai_commits:     int = 0
    ai_examples:    list = field(default_factory=list)   # up to 3 sample SHAs

    @property
    def score(self) -> float:
        """
        Composite score for ranking:
          - AI commit evidence is the primary signal (×10)
          - repo size (log-scaled) indicates class-count richness
          - stars indicate real-world relevance
        """
        import math
        return (
            self.ai_commits * 10
            + math.log1p(self.size_kb) * 2
            + math.log1p(self.stars)
        )


# ── GitHub helpers ───────────────────────────────────────────────────────────

class GitHubClient:
    def __init__(self, token: Optional[str]):
        self.session = requests.Session()
        self.session.headers.update({
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def get(self, path: str, params: dict = None) -> dict:
        url = path if path.startswith("http") else f"{GITHUB_API}{path}"
        while True:
            r = self.session.get(url, params=params, timeout=20)
            if r.status_code == 403 and "rate limit" in r.text.lower():
                reset = int(r.headers.get("X-RateLimit-Reset", time.time() + 60))
                wait  = max(1, reset - int(time.time())) + 2
                print(f"  [rate limit] waiting {wait}s …", flush=True)
                time.sleep(wait)
                continue
            if r.status_code == 404:
                return {}
            r.raise_for_status()
            return r.json()

    def search_repos(self, query: str, per_page: int = 30) -> list[dict]:
        data = self.get("/search/repositories", {
            "q": query,
            "sort": "stars",
            "order": "desc",
            "per_page": per_page,
        })
        return data.get("items", [])

    def recent_commits(self, full_name: str, count: int) -> list[dict]:
        data = self.get(f"/repos/{full_name}/commits", {"per_page": count})
        if isinstance(data, list):
            return data
        return []


# ── Analysis logic ───────────────────────────────────────────────────────────

def has_ai_coauthor(commit: dict) -> tuple[bool, str]:
    """Return (found, snippet) if the commit message contains an AI signal."""
    msg = ""
    try:
        msg = (commit.get("commit", {}).get("message") or "").lower()
    except Exception:
        pass
    for pattern in AI_COAUTHOR_PATTERNS:
        if pattern in msg:
            sha = commit.get("sha", "")[:7]
            # grab the matching line for display
            for line in msg.splitlines():
                if pattern in line:
                    return True, f"{sha}: {line.strip()[:80]}"
            return True, f"{sha}: (matched '{pattern}')"
    return False, ""


def scan_repo(client: GitHubClient, repo: dict) -> RepoCandidate:
    full_name = repo["full_name"]
    candidate = RepoCandidate(
        full_name   = full_name,
        html_url    = repo["html_url"],
        description = (repo.get("description") or "")[:100],
        stars       = repo.get("stargazers_count", 0),
        size_kb     = repo.get("size", 0),
        open_issues = repo.get("open_issues_count", 0),
        pushed_at   = (repo.get("pushed_at") or "")[:10],
    )

    commits = client.recent_commits(full_name, COMMITS_PER_REPO)
    for commit in commits:
        found, snippet = has_ai_coauthor(commit)
        if found:
            candidate.ai_commits += 1
            if len(candidate.ai_examples) < 3:
                candidate.ai_examples.append(snippet)

    return candidate


# ── Output ───────────────────────────────────────────────────────────────────

def print_results(candidates: list[RepoCandidate], top_n: int):
    ranked = sorted(candidates, key=lambda c: c.score, reverse=True)[:top_n]

    print()
    print("=" * 72)
    print(f"  TOP {len(ranked)} JAVA REPOS FOR STRUCTURE202 ANALYSIS")
    print("=" * 72)

    for i, c in enumerate(ranked, 1):
        ai_label = f"{c.ai_commits} AI commit(s)" if c.ai_commits else "no AI commits detected"
        print(f"\n#{i}  {c.full_name}")
        print(f"    {c.html_url}")
        print(f"    ★ {c.stars:,}  |  size {c.size_kb//1024} MB  |  "
              f"issues {c.open_issues}  |  last push {c.pushed_at}")
        if c.description:
            print(f"    {c.description}")
        print(f"    AI signal: {ai_label}")
        for ex in c.ai_examples:
            print(f"      → {ex}")
        print(f"    score: {c.score:.1f}")

    if not ranked:
        print("  (no candidates found)")

    print()
    print("Clone the top candidate with:")
    if ranked:
        print(f"  git clone {ranked[0].html_url}")
    print()


# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"),
                        help="GitHub personal access token (or set GITHUB_TOKEN env var)")
    parser.add_argument("--top",   type=int, default=5,
                        help="How many results to print (default: 5)")
    parser.add_argument("--query", default=SEARCH_QUERY,
                        help="Override the GitHub search query")
    args = parser.parse_args()

    if not args.token:
        print("WARNING: no GitHub token — unauthenticated API rate limit is 10 req/min.",
              file=sys.stderr)
        print("         Pass --token or set GITHUB_TOKEN for 5 000 req/h.", file=sys.stderr)
        print()

    client = GitHubClient(args.token)

    print(f"Searching GitHub: {args.query}")
    repos = client.search_repos(args.query, per_page=RESULTS_PER_PAGE)
    repos = repos[:MAX_REPOS_TO_SCAN]
    print(f"Found {len(repos)} repos to scan …\n")

    candidates: list[RepoCandidate] = []
    for idx, repo in enumerate(repos, 1):
        name = repo["full_name"]
        print(f"  [{idx:2}/{len(repos)}] {name} …", end=" ", flush=True)
        try:
            c = scan_repo(client, repo)
            candidates.append(c)
            ai_tag = f"  ✓ {c.ai_commits} AI" if c.ai_commits else ""
            print(f"★{c.stars:,}  {c.size_kb//1024}MB{ai_tag}")
        except Exception as e:
            print(f"ERROR: {e}")

    print_results(candidates, args.top)


if __name__ == "__main__":
    main()
