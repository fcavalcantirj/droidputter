#!/bin/bash
# ralph-claude.sh — Ralph loop on the Claude Code CLI engine.
#   ./ralph-claude.sh 3        # up to three tasks
cd "$(dirname "$0")" || exit 1
ENGINE=claude exec ./ralph.sh "$@"
