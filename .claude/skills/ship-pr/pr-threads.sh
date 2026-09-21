#!/usr/bin/env bash
# Audit review threads on a PR — the list `gh pr view` does not give you.
#
#   bash pr-threads.sh <pr-number> [owner/repo] [--all]   list threads
#   bash pr-threads.sh --show <threadId>                  print one thread in full
#
# List mode prints one line per UNRESOLVED thread:
#   [OPEN] [outdated] <nodeId>  <path>:<line>  <first>-><last>  comments=N  <finding>
# --all also prints resolved ones as [resolved by <login>].
#
# The nodeId is what addPullRequestReviewThreadReply and resolveReviewThread take.
#
# `comments=N` counts EVERY comment including the one that opened the thread, so
# comments=1 means nobody has answered. <first>-><last> is the opening and the most
# recent author: when they differ, someone replied — but note CodeRabbit often replies
# inside its own thread, so first==last==coderabbitai with N>1 is still unanswered by
# a human.
#
# The preview column drops the whole collapsed <details> block (CodeRabbit puts its
# "Script executed" analysis chain there) and its `_🟠 Major_ | ...` badge line, then
# prefers the bolded finding title. It is still only a preview — use --show before
# acting on a finding.
#
# Uses only `gh` (its --jq is built in); standalone jq is not installed on Windows here.
#
# Threads are only half the feedback: CodeRabbit nitpicks, duplicates and
# outside-diff-range findings live in review BODIES and have no thread — see the
# "findings with no thread" section of SKILL.md.
set -euo pipefail

# ─── --show <threadId>: full text of one thread ──────────────────────────────
if [ "${1:-}" = "--show" ]; then
  TID="${2:?usage: pr-threads.sh --show <threadId>}"
  gh api graphql -F id="$TID" -f query='query($id:ID!){
    node(id:$id){
      ... on PullRequestReviewThread{
        isResolved isOutdated path line originalLine
        resolvedBy{login}
        comments(first:50){nodes{author{login} createdAt body}}
      }
    }
  }' --jq '.data.node
    | "path:     " + (.path // "?") + ":" + ((.line // .originalLine // 0)|tostring)
    + "\nresolved: " + (if .isResolved then "yes, by " + (.resolvedBy.login // "?") else "no" end)
      + (if .isOutdated then "  (outdated — the line moved; check current code)" else "" end)
    + "\n"
    + ([.comments.nodes[] | "───── " + (.author.login // "?") + " " + .createdAt + " ─────\n" + .body] | join("\n\n"))'
  exit 0
fi

PR="${1:?usage: pr-threads.sh <pr-number> [owner/repo] [--all]  |  --show <threadId>}"
shift
# owner/repo and --all in either order, both optional.
REPO="labsai/EDDI"
SHOW_ALL=""
for arg in "$@"; do
  case "$arg" in
    --all) SHOW_ALL="--all" ;;
    -*)    echo "unknown option: $arg" >&2; exit 2 ;;
    *)     REPO="$arg" ;;
  esac
done
case "$PR" in
  ''|*[!0-9]*) echo "first argument must be the PR number; got '$PR'" >&2
               echo "usage: pr-threads.sh <pr-number> [owner/repo] [--all]  |  --show <threadId>" >&2
               exit 2 ;;
esac
OWNER="${REPO%%/*}"
NAME="${REPO##*/}"

# $endCursor + pageInfo is what lets `gh api graphql --paginate` walk the pages itself.
QUERY='query($owner:String!,$name:String!,$pr:Int!,$endCursor:String){
  repository(owner:$owner,name:$name){
    pullRequest(number:$pr){
      author{login}
      reviewThreads(first:100,after:$endCursor){
        pageInfo{hasNextPage endCursor}
        nodes{
          id isResolved isOutdated path line originalLine
          resolvedBy{login}
          opener: comments(first:1){nodes{author{login} body}}
          cmts: comments(last:100){totalCount nodes{author{login}}}
        }
      }
    }
  }
}'

# First line of the first comment that is actual prose: skip blanks, CodeRabbit's
# `_🟠 Major_ | ...` badge line, HTML comment markers and blockquote callouts.
FORMAT='.data.repository.pullRequest as $pr
  | ($pr.author.login // "?") as $me
  | $pr.reviewThreads.nodes[]
  | (if .cmts.totalCount > 100 then .cmts.nodes else .cmts.nodes[1:] end) as $replies
  | ([ $replies[] | select((.author.login // "") == $me) ] | length > 0) as $answered
  | (if .isResolved then "[resolved by " + (.resolvedBy.login // "?") + "] " else "[OPEN]     " end)
  + (if .isOutdated then "[outdated] " else "" end)
  + (if $answered then "" else "[NO-REPLY] " end)
  + .id
  + "  " + (.path // "?") + ":" + ((.line // .originalLine // 0) | tostring)
  + "  " + (.opener.nodes[0].author.login // "?")
  + "->" + (.cmts.nodes[-1].author.login // "?")
  + "  comments=" + (.cmts.totalCount | tostring)
  + "  " + (
      [ (.opener.nodes[0].body // "") | split("\n")
        | reduce .[] as $l ({d:0,o:[]};
            if   ($l | startswith("<details"))  then .d = .d + 1
            elif ($l | startswith("</details")) then .d = .d - 1
            elif .d == 0                        then .o = .o + [$l]
            else . end)
        | .o[]
        | select(length > 0
                 and (startswith("_")     | not)
                 and (startswith("<!--")  | not)
                 and (startswith("<summary") | not)
                 and (startswith(">")     | not)) ] as $prose
      | ( [ $prose[] | select(startswith("**") or startswith("## ")) ][0]
          // $prose[0]
          // "(no prose - use --show)" )
      | .[0:100])'

ALL=$(gh api graphql --paginate \
  -F owner="$OWNER" -F name="$NAME" -F pr="$PR" \
  -f query="$QUERY" --jq "$FORMAT")

if [ "$SHOW_ALL" = "--all" ]; then
  printf '%s\n' "$ALL"
else
  printf '%s\n' "$ALL" | grep '^\[OPEN\]' || true
fi

TOTAL=$(printf '%s\n' "$ALL" | grep -c . || true)
OPEN=$(printf '%s\n' "$ALL" | grep -c '^\[OPEN\]' || true)
# Authorship, not comment count: the bot replying to itself is not an answer.
SILENT=$(printf '%s\n' "$ALL" | grep -c '\[NO-REPLY\]' || true)
SILENT_RESOLVED=$(printf '%s\n' "$ALL" | grep '^\[resolved' | grep -c '\[NO-REPLY\]' || true)

echo
echo "threads: $TOTAL total, $OPEN unresolved, $SILENT with no reply from you"
if [ "$SILENT_RESOLVED" -gt 0 ]; then
  echo
  echo "!! $SILENT_RESOLVED RESOLVED thread(s) have no reply from you -- outstanding, not done."
  echo "   A bot resolves its own thread as soon as your push makes it outdated, so the"
  echo "   findings you actually fixed are the ones most likely to close unanswered."
  echo "   List them:  bash $0 $PR --all | grep '\[NO-REPLY\]'"
fi
echo "read one in full:  bash $0 --show <threadId>"
echo "reminder: nitpicks, duplicates and outside-diff-range findings live in review"
echo "bodies, not threads, and some are Major — see SKILL.md"
