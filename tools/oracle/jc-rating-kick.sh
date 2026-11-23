#!/bin/sh
# Asks GitHub to run the "rating" workflow of JacCollector (backup for GitHub's own schedule,
# which sometimes does not fire). Token: /etc/jc-rating-kick.env, GH_TOKEN=... — a fine-grained
# token for this one repository with only "Actions: read and write".
set -eu
: "${GH_TOKEN:?GH_TOKEN missing (EnvironmentFile /etc/jc-rating-kick.env)}"
code=$(curl -s -o /tmp/jc-rating-kick.out -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/Steklorez/JacCollector/actions/workflows/rating.yml/dispatches \
  -d '{"ref":"main"}')
if [ "$code" = "204" ]; then echo "rating workflow started"; else echo "GitHub answered HTTP $code: $(head -c 300 /tmp/jc-rating-kick.out)"; exit 1; fi
