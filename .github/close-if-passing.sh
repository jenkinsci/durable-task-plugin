#!/bin/bash
set -euo pipefail

# Exit early if this is not a Jenkins check that completed
if [[ "$(jq -r .check_run.name < "$GITHUB_EVENT_PATH")" != "Jenkins" ]]; then
  echo "Not a Jenkins check, exiting"
  exit 0
fi

if [[ "$(jq -r .check_run.status < "$GITHUB_EVENT_PATH")" != "completed" ]]; then
  echo "Check not completed, exiting"
  exit 0
fi

# Get commit SHA from the check run
check_run_sha="$(jq -r .check_run.head_sha < "$GITHUB_EVENT_PATH")"
check_run_conclusion="$(jq -r .check_run.conclusion < "$GITHUB_EVENT_PATH")"

echo "Processing Jenkins check for commit: $check_run_sha"
echo "Check conclusion: $check_run_conclusion"

# Get the PR associated with this commit
pr_data=$(gh api graphql -f query='
  query($owner: String!, $repo: String!, $sha: GitObjectID!) {
    repository(owner: $owner, name: $repo) {
      object(oid: $sha) {
        ... on Commit {
          associatedPullRequests(first: 1) {
            nodes {
              number
              labels(first: 10) {
                nodes {
                  name
                }
              }
              isDraft
              headRefOid
            }
          }
        }
      }
    }
  }' -f owner="$GITHUB_REPOSITORY_OWNER" -f repo="${GITHUB_REPOSITORY#*/}" -f sha="$check_run_sha")

# Extract PR data
pr_number=$(echo "$pr_data" | jq -r '.data.repository.object.associatedPullRequests.nodes[0].number // empty')
pr_head_sha=$(echo "$pr_data" | jq -r '.data.repository.object.associatedPullRequests.nodes[0].headRefOid // empty')

# Exit early if no associated PR found
if [[ -z "$pr_number" ]]; then
  echo "No associated pull request found for commit $check_run_sha, exiting"
  exit 0
fi

pr_labels=$(echo "$pr_data" | jq -r '.data.repository.object.associatedPullRequests.nodes[0].labels.nodes[].name' | tr '\n' ',' | sed 's/,$//')
pr_draft=$(echo "$pr_data" | jq -r '.data.repository.object.associatedPullRequests.nodes[0].isDraft // empty')

echo "Labels: $pr_labels"
echo "Draft: $pr_draft"

# Check if this PR has the close-if-passing label
if [[ "$pr_labels" != *"close-if-passing"* ]]; then
  echo "PR does not have close-if-passing label, exiting"
  exit 0
fi

echo "PR has close-if-passing label, processing..."

# Verify commit matches the PR head
if [[ "$check_run_sha" != "$pr_head_sha" ]]; then
  echo "Head SHA mismatch (check: $check_run_sha, PR head: $pr_head_sha), exiting"
  exit 0
fi

# Handle based on CI conclusion
if [[ "$check_run_conclusion" == "success" ]]; then
  echo "CI passed, closing PR #$pr_number"

  # Leave a comment and close the PR
  gh pr comment "$pr_number" --body "✅ CI passed successfully. Closing this PR as requested by the \`close-if-passing\` label." --repo "$GITHUB_REPOSITORY"
  gh pr close "$pr_number" --repo "$GITHUB_REPOSITORY"

  echo "PR #$pr_number closed due to successful CI"
else
  echo "CI failed with conclusion: $check_run_conclusion"

  # Leave a comment
  gh pr comment "$pr_number" --body "❌ CI failed with conclusion: \`$check_run_conclusion\`. Converting to ready for review so it can be investigated." --repo "$GITHUB_REPOSITORY"

  # Convert from draft to ready for review if it's currently a draft
  if [[ "$pr_draft" == "true" ]]; then
    gh pr ready "$pr_number" --repo "$GITHUB_REPOSITORY"
    echo "PR #$pr_number converted from draft to ready for review"
  else
    echo "PR #$pr_number is already ready for review"
  fi
fi
