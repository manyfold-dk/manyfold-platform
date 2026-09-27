#!/bin/bash
# Ralph - Autonomous AI Coding Loop
#
# Runs Claude Code in a loop, executing tasks from prd.md until all pass.
# Each iteration wipes context for fresh starts.
#
# Usage:
#   ./ralph.sh [options]
#
# Options:
#   --prd FILE      Task file (default: prd.md in current directory)
#   --prompt FILE   Prompt file (default: prompt.md in current directory)
#   --max-iterations N   Maximum iterations before stopping (default: 50)
#   --dry-run       Show what would be executed without running
#
# The key insight: AI gets worse as context grows.
# Ralph wipes context after every single task for fresh starts.

set -euo pipefail

# Defaults
PRD_FILE="prd.md"
PROMPT_FILE="prompt.md"
MAX_ITERATIONS=50
DRY_RUN=false
ACTIVITY_LOG="activity.md"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  --prd FILE           Task file (default: prd.md)"
    echo "  --prompt FILE        Prompt file (default: prompt.md)"
    echo "  --max-iterations N   Maximum iterations (default: 50)"
    echo "  --dry-run            Show what would be executed"
    echo "  -h, --help           Show this help"
    exit 0
}

log() {
    local level=$1
    shift
    local msg="$*"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    case $level in
        INFO)  echo -e "${BLUE}[INFO]${NC} $msg" ;;
        OK)    echo -e "${GREEN}[OK]${NC} $msg" ;;
        WARN)  echo -e "${YELLOW}[WARN]${NC} $msg" ;;
        ERROR) echo -e "${RED}[ERROR]${NC} $msg" ;;
    esac

    # Append to activity log
    echo "[$timestamp] [$level] $msg" >> "$ACTIVITY_LOG"
}

# Count the task status lines in the PRD whose Passes field has the given
# value. A status line is a list item such as "- **Passes**: false" (the
# template's form) or "- Passes: false"; prose that mentions "Passes: true"
# does not count. grep -c prints 0 and exits 1 when nothing matches, so the
# exit status is ignored rather than answered with a second "0".
count_passes() {
    local value=$1
    local count
    count=$(grep -cE "^[[:space:]]*[-*][[:space:]]+(\*\*)?Passes(\*\*)?:(\*\*)?[[:space:]]*${value}([[:space:]]|$)" "$PRD_FILE" 2>/dev/null) || true
    echo "${count:-0}"
}

check_all_tasks_complete() {
    # Check if all tasks in prd.md have "Passes: true"
    local incomplete
    incomplete=$(count_incomplete_tasks)
    [[ "$incomplete" -eq 0 ]]
}

count_incomplete_tasks() {
    count_passes false
}

count_complete_tasks() {
    count_passes true
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --prd)
            PRD_FILE="$2"
            shift 2
            ;;
        --prompt)
            PROMPT_FILE="$2"
            shift 2
            ;;
        --max-iterations)
            MAX_ITERATIONS="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

# Validate files exist
if [[ ! -f "$PROMPT_FILE" ]]; then
    echo -e "${RED}Error: Prompt file not found: $PROMPT_FILE${NC}"
    echo "Create a prompt.md with instructions for Claude."
    exit 1
fi

if [[ ! -f "$PRD_FILE" ]]; then
    echo -e "${RED}Error: PRD file not found: $PRD_FILE${NC}"
    echo "Create a prd.md with tasks to complete."
    exit 1
fi

# Initialize activity log
{
    echo "# Ralph Activity Log"
    echo ""
    echo "Started: $(date)"
    echo "PRD: $PRD_FILE"
    echo "Prompt: $PROMPT_FILE"
    echo ""
    echo "---"
    echo ""
} > "$ACTIVITY_LOG"

log INFO "Starting Ralph autonomous coding loop"
log INFO "PRD file: $PRD_FILE"
log INFO "Prompt file: $PROMPT_FILE"
log INFO "Max iterations: $MAX_ITERATIONS"

# Check if already complete
if check_all_tasks_complete; then
    log OK "All tasks already complete!"
    exit 0
fi

incomplete=$(count_incomplete_tasks)
complete=$(count_complete_tasks)
log INFO "Tasks: $complete complete, $incomplete remaining"

if $DRY_RUN; then
    log WARN "Dry run mode - not executing"
    echo ""
    echo "Would execute:"
    echo "  claude -p \"\$(cat $PROMPT_FILE)\" --allowedTools \"Edit,Write,Bash,Read\""
    echo ""
    echo "In a loop until all tasks in $PRD_FILE have 'Passes: true'"
    exit 0
fi

# Main loop
iteration=1
while [[ $iteration -le $MAX_ITERATIONS ]]; do
    log INFO "=== Iteration $iteration of $MAX_ITERATIONS ==="

    # Check if complete
    if check_all_tasks_complete; then
        log OK "All tasks complete after $iteration iterations!"
        break
    fi

    incomplete=$(count_incomplete_tasks)
    log INFO "$incomplete tasks remaining"

    # Run Claude with fresh context each time
    # The prompt tells Claude to:
    # 1. Read the prd.md
    # 2. Pick the most important incomplete task
    # 3. Implement it
    # 4. Test it
    # 5. Mark it done if tests pass

    log INFO "Running Claude (fresh context)..."

    # Run Claude with the prompt
    # Using --dangerously-skip-permissions for autonomous operation
    # The prompt.md should contain all necessary instructions
    if ! claude -p "$(cat "$PROMPT_FILE")" --allowedTools "Edit,Write,Bash,Read" 2>&1; then
        log WARN "Claude exited with non-zero status"
    fi

    # Log completion status
    complete=$(count_complete_tasks)
    incomplete=$(count_incomplete_tasks)
    log INFO "After iteration $iteration: $complete complete, $incomplete remaining"

    ((iteration++))
done

# Final status
if check_all_tasks_complete; then
    log OK "Ralph completed successfully!"
    log INFO "Total iterations: $((iteration - 1))"
    exit 0
else
    log WARN "Reached max iterations ($MAX_ITERATIONS) with tasks remaining"
    incomplete=$(count_incomplete_tasks)
    log WARN "$incomplete tasks still incomplete"
    exit 1
fi
