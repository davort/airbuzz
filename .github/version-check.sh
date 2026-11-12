#!/usr/bin/env bash
# Checks the bookkeeping that two threads pushing to main can break without the build noticing:
# merge conflict markers anywhere in the repo, one versionCode and versionName in app/build.gradle.kts with their own
# line under "Versions" in README.md, no versionCode listed twice there, and, compared with a base commit (the push's
# previous main, or a pull request's base), a version that never goes back and whose code and name change together.
#
# Usage: .github/version-check.sh [base-commit]. Run it before pushing a version bump: .github/version-check.sh origin/main
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

failed=0
fail() { echo "::error::$*"; failed=1; }

if markers="$(git grep -nE '^(<<<<<<<|>>>>>>>)( |$)' -- . ':!third_party')"; then
  fail "Merge conflict markers are committed:"$'\n'"$markers"
fi

version_of() { # prints "versionCode versionName" from build.gradle.kts text on stdin
  local text code name
  text="$(cat)"
  code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' <<<"$text")"
  name="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' <<<"$text")"
  [[ "$code" =~ ^[0-9]+$ && -n "$name" && "$name" != *$'\n'* ]] || return 1
  echo "$code $name"
}

if ! read -r code name < <(version_of < app/build.gradle.kts); then
  fail "app/build.gradle.kts needs exactly one versionCode and one versionName"
  exit 1
fi
echo "Version $name (versionCode $code)"

versions="$(sed -n '/^## Versions$/,/^## /p' README.md)"
if ! grep -qF -- "- **$name** (versionCode $code):" <<<"$versions"; then
  fail "README.md has no Versions line \"- **$name** (versionCode $code): ...\""
fi
if dupes="$(grep -oE '\(versionCode [0-9]+\)' <<<"$versions" | sort | uniq -d | grep .)"; then
  fail "README.md lists one versionCode on more than one line: $(echo $dupes)"
fi

base="${1:-}"
if [[ -n "$base" && ! "$base" =~ ^0+$ ]]; then
  if ! base_text="$(git show "$base:app/build.gradle.kts" 2>/dev/null)"; then
    echo "::warning::Base $base isn't in this checkout; skipped the comparison with it"
  elif read -r base_code base_name < <(version_of <<<"$base_text"); then
    if ((code < base_code)); then
      fail "versionCode went back from $base_code ($base_name) to $code ($name)"
    elif [[ "$code" != "$base_code" && "$name" == "$base_name" ]]; then
      fail "versionCode changed from $base_code to $code but versionName stayed $name"
    elif [[ "$code" == "$base_code" && "$name" != "$base_name" ]]; then
      fail "versionName changed from $base_name to $name but versionCode stayed $code"
    fi
  fi
fi

exit "$failed"
