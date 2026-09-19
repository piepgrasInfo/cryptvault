#!/usr/bin/env bash
# Keeps the public GitHub repository in step with this one (BUILD_BRIEF.md §13, GPL source
# availability). The public repository is a full clone living in github/ inside the project
# folder; this script creates it on first use and fast-forwards it afterwards. Pushing to GitHub
# stays a manual step so nothing leaves the house by accident:
#
#   tools/sync_github_mirror.sh          # create or update github/ from the current master
#   git -C github remote add origin git@github.com:<owner>/cryptvault.git   # once
#   git -C github push -u origin master
#
# Nothing private is in this repository's history (providers.properties, keystore.properties
# and *.jks are gitignored; only their .example files are tracked), so the clone carries the
# whole history, as the GPL and honest release notes both want.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIRROR="$ROOT/github"
BRANCH="master"

if [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
  echo "warning: uncommitted changes in $ROOT are not part of the mirror (it follows commits only)" >&2
fi
if git -C "$ROOT" ls-files | grep -qiE '^(providers|keystore)\.properties$|\.jks$|\.keystore$'; then
  echo "refusing: a credential file is tracked in $ROOT" >&2; exit 1
fi

if [ ! -d "$MIRROR/.git" ]; then
  git clone --no-hardlinks --branch "$BRANCH" "$ROOT" "$MIRROR"
  git -C "$MIRROR" remote remove origin          # GitHub becomes 'origin' when the developer adds it
  git -C "$MIRROR" remote add house "$ROOT"
  echo "created $MIRROR at $(git -C "$MIRROR" rev-parse --short HEAD)"
else
  git -C "$MIRROR" fetch --quiet house "$BRANCH"
  git -C "$MIRROR" merge --ff-only FETCH_HEAD
  echo "updated $MIRROR to $(git -C "$MIRROR" rev-parse --short HEAD)"
fi

if git -C "$MIRROR" remote get-url origin >/dev/null 2>&1; then
  echo "next: git -C github push origin $BRANCH"
else
  echo "next: git -C github remote add origin git@github.com:<owner>/cryptvault.git && git -C github push -u origin $BRANCH"
fi
