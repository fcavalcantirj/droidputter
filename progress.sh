#!/bin/bash

# Count passed and total requirements in the droidputter PRD.
# jq is formatting-immune (engines rewrite the ledger with arbitrary JSON
# spacing); the grep fallback relies on one "passes" flag per line.

prd_file="${PRD_FILE:-spec.json}"

if [ ! -f "$prd_file" ]; then
  echo "0/0 (0%) - PRD not found"
  exit 0
fi

if command -v jq >/dev/null 2>&1 && total=$(jq 'length' "$prd_file" 2>/dev/null); then
  passed=$(jq '[.[] | select(.passes == true)] | length' "$prd_file" 2>/dev/null)
else
  total=$(grep -c '"passes"' "$prd_file" | tr -d '\n')
  passed=$(grep -cE '"passes"[[:space:]]*:[[:space:]]*true' "$prd_file" | tr -d '\n')
fi

total=${total:-0}
passed=${passed:-0}

# Human-only checks the loop journaled instead of skipping — pending until a
# human confirms them. 100% with UAT pending is NOT "everything verified".
uat=0
if [ -f progress.txt ]; then
  uat=$(grep -c 'UAT:' progress.txt 2>/dev/null | tr -d '\n')
fi
uat=${uat:-0}
suffix=""
[ "$uat" -gt 0 ] 2>/dev/null && suffix=" · ${uat} UAT pending"

if [ "$total" -eq 0 ]; then
  echo "0/0 (0%)${suffix}"
else
  percent=$((passed * 100 / total))
  echo "${passed}/${total} (${percent}%)${suffix}"
fi
