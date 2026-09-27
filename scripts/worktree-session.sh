#!/usr/bin/env bash
#
# worktree-session.sh - Manage shared agent sessions with Git worktrees
#
# This script helps you run multiple isolated agent sessions in parallel
# using Git worktrees. Each session gets its own branch and working directory.
#
# Usage:
#   ./scripts/worktree-session.sh start <task-name>      # Start new session
#   ./scripts/worktree-session.sh list                   # List all sessions
#   ./scripts/worktree-session.sh switch <task-name>     # Switch to existing session
#   ./scripts/worktree-session.sh cleanup <task-name>    # Remove session worktree
#   ./scripts/worktree-session.sh cleanup -y <task-name> # Remove without confirmation
#   ./scripts/worktree-session.sh cleanup --force <task-name> # Also remove uncommitted/unmerged work
#   ./scripts/worktree-session.sh cleanup-all            # Remove all sessions under WORKTREE_BASE
#   ./scripts/worktree-session.sh status                 # Show current worktree status
#   ./scripts/worktree-session.sh help                   # Show this help

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKTREE_BASE="${MANYFOLD_WORKTREE_BASE:-$HOME/.manyfold-worktrees/manyfold-platform}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Helper functions
print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

print_success() {
    echo -e "${GREEN}✔${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✖${NC} $1"
}

normalize_task_name() {
    echo "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's#[[:space:]/]+#-#g; s#[^a-z0-9._-]#-#g; s#-+#-#g; s#^-##; s#-$##'
}

link_repo_file() {
    local source_path=$1
    local target_path=$2
    local description=$3

    if [ ! -f "$source_path" ]; then
        return 0
    fi

    mkdir -p "$(dirname "$target_path")"

    if [ -e "$target_path" ] && [ ! -L "$target_path" ]; then
        print_warning "Skipped $description because $target_path already exists and is not a symlink"
        return 0
    fi

    ln -sfn "$source_path" "$target_path"
    print_success "Linked $description"
}

link_repo_path() {
    local source_path=$1
    local target_path=$2
    local description=$3

    if [ ! -e "$source_path" ]; then
        return 0
    fi

    mkdir -p "$(dirname "$target_path")"

    if [ -e "$target_path" ] && [ ! -L "$target_path" ]; then
        print_warning "Skipped $description because $target_path already exists and is not a symlink"
        return 0
    fi

    ln -sfn "$source_path" "$target_path"
    print_success "Linked $description"
}

find_session_dir() {
    local task_name=$1
    local worktree_dir="$WORKTREE_BASE/$task_name"

    # "." and ".." survive normalization but name WORKTREE_BASE or its parent
    if [ "$task_name" = "." ] || [ "$task_name" = ".." ]; then
        return 1
    fi

    if [ -d "$worktree_dir" ]; then
        echo "$worktree_dir"
        return 0
    fi

    return 1
}

show_help() {
    echo -e "${CYAN}Manyfold Worktree Manager${NC} - Manage shared agent worktrees"
    echo ""
    echo -e "${YELLOW}USAGE:${NC}"
    echo "    ./scripts/worktree-session.sh <command> [arguments]"
    echo ""
    echo -e "${YELLOW}COMMANDS:${NC}"
    echo -e "    ${GREEN}start <task-name>${NC}      Create new worktree for an isolated session"
    echo -e "    ${GREEN}list${NC}                   List all active session worktrees"
    echo -e "    ${GREEN}switch <task-name>${NC}     Switch to existing session"
    echo -e "    ${GREEN}cleanup <task-name>${NC}    Remove a specific session worktree"
    echo -e "    ${GREEN}cleanup -y <task-name>${NC} Remove without confirmation prompt"
    echo -e "    ${GREEN}cleanup --force <task>${NC} Remove even with uncommitted changes or an unmerged branch"
    echo -e "    ${GREEN}cleanup-all${NC}            Remove all session worktrees under the worktree root"
    echo -e "    ${GREEN}cleanup-all -y${NC}         Remove all without confirmation prompt"
    echo -e "    ${GREEN}cleanup-all --force${NC}    Also remove sessions with uncommitted or unmerged work"
    echo -e "    ${GREEN}status${NC}                 Show current worktree and Git status"
    echo -e "    ${GREEN}help${NC}                   Show this help message"
    echo ""
    echo -e "${YELLOW}EXAMPLES:${NC}"
    echo "    # Start working on authentication refactor"
    echo "    ./scripts/worktree-session.sh start auth-refactor"
    echo ""
    echo "    # Start another session for adding metrics"
    echo "    ./scripts/worktree-session.sh start add-metrics"
    echo ""
    echo "    # List all active sessions"
    echo "    ./scripts/worktree-session.sh list"
    echo ""
    echo "    # Switch to the auth-refactor session"
    echo "    ./scripts/worktree-session.sh switch auth-refactor"
    echo ""
    echo "    # Clean up when done"
    echo "    ./scripts/worktree-session.sh cleanup auth-refactor"
    echo ""
    echo -e "${YELLOW}HOW IT WORKS:${NC}"
    echo "    - Each session creates a worktree in: ${WORKTREE_BASE}/<task-name>"
    echo "    - Each session gets its own branch: feature/<task-name>"
    echo "    - Task names are normalized to lowercase dash form"
    echo "    - Repo-specific environment symlinks are created when their sources exist"
    echo "    - The shared agent workflow uses the same directory and branch layout"
    echo "    - Sessions are completely isolated - no conflicts between them"
    echo "    - When done, merge the branch back to main and cleanup the worktree"
    echo "    - Cleanup refuses a worktree with uncommitted changes or an unmerged"
    echo "      branch; --force overrides that check"
    echo ""
    echo -e "${YELLOW}WORKTREE LOCATION:${NC}"
    echo "    Canonical root: ${WORKTREE_BASE}"
    echo ""
}

check_git_repo() {
    if ! git -C "$PROJECT_DIR" rev-parse --git-dir > /dev/null 2>&1; then
        print_error "Not a Git repository: $PROJECT_DIR"
        exit 1
    fi
}

is_in_worktree() {
    local git_dir
    git_dir=$(git rev-parse --git-dir 2>/dev/null)
    [[ "$git_dir" == *".git/worktrees"* ]]
}

get_current_worktree_name() {
    if is_in_worktree; then
        basename "$(git rev-parse --show-toplevel)"
    else
        echo "main"
    fi
}

add_worktree_trust() {
    local worktree_dir=$1

    if ! command -v jq &> /dev/null; then
        print_warning "jq not found - skipping automatic trust setup"
        print_info "You'll need to approve the directory trust prompt manually"
        return
    fi

    if [ -f "$HOME/.claude.json" ]; then
        print_info "Adding worktree to trusted directories..."
        jq --arg path "$worktree_dir" \
           '.projects[$path] = {
              "allowedTools": [],
              "hasTrustDialogAccepted": true,
              "mcpContextUris": [],
              "mcpServers": {},
              "enabledMcpjsonServers": [],
              "disabledMcpjsonServers": []
           }' \
           "$HOME/.claude.json" > "$HOME/.claude.json.tmp" && \
           mv "$HOME/.claude.json.tmp" "$HOME/.claude.json"
        print_success "Worktree auto-trusted"
    fi
}

create_repo_symlinks() {
    local worktree_dir=$1

    link_repo_file "$PROJECT_DIR/.env" "$worktree_dir/.env" ".env from main repository"
    link_repo_file "$PROJECT_DIR/.env.cloud" "$worktree_dir/.env.cloud" ".env.cloud from main repository"
    link_repo_file "$PROJECT_DIR/.env.r2" "$worktree_dir/.env.r2" ".env.r2 from main repository"
    link_repo_file "$PROJECT_DIR/.env.agents" "$worktree_dir/.env.agents" ".env.agents from main repository"
    link_repo_path "$PROJECT_DIR/infrastructure/.working" "$worktree_dir/infrastructure/.working" "infrastructure/.working from main repository"
}

start_session() {
    local task_name=$1
    local normalized_task_name
    local existing_dir

    if [ -z "$task_name" ]; then
        print_error "Task name is required"
        echo "Usage: $0 start <task-name>"
        exit 1
    fi

    normalized_task_name=$(normalize_task_name "$task_name")
    if [ -z "$normalized_task_name" ]; then
        print_error "Task name '$task_name' cannot be normalized into a valid worktree name"
        exit 1
    fi
    task_name="$normalized_task_name"

    if existing_dir=$(find_session_dir "$task_name"); then
        print_warning "Session '$task_name' already exists at: $existing_dir"
        print_info "Use 'switch $task_name' to resume it or 'cleanup $task_name' to remove it"
        exit 1
    fi

    local worktree_dir="$WORKTREE_BASE/$task_name"
    local branch_name="feature/$task_name"

    print_info "Creating worktree for session: $task_name"
    print_info "Location: $worktree_dir"
    print_info "Branch: $branch_name"

    mkdir -p "$WORKTREE_BASE"

    cd "$PROJECT_DIR"
    git worktree prune 2>/dev/null || true

    if git worktree add "$worktree_dir" -b "$branch_name" 2>/dev/null; then
        print_success "Worktree created successfully"
    else
        if git worktree add "$worktree_dir" "$branch_name" 2>/dev/null; then
            print_success "Worktree created (using existing branch)"
        else
            print_error "Failed to create worktree"
            print_info "This might happen if:"
            print_info "  - The branch already has a worktree elsewhere"
            print_info "  - There are permission issues"
            print_info "Try: git worktree list"
            exit 1
        fi
    fi

    add_worktree_trust "$worktree_dir"

    create_repo_symlinks "$worktree_dir"

    print_success "Session '$task_name' ready!"
    echo ""
    print_info "To start working:"
    echo -e "  ${CYAN}cd $worktree_dir${NC}"
    echo -e "  ${CYAN}<start-your-agent>${NC}"
    echo ""
    print_info "Or run: ${CYAN}./scripts/worktree-session.sh switch $task_name${NC}"
}

list_sessions() {
    print_info "Active session worktrees:"
    echo ""

    cd "$PROJECT_DIR"
    git worktree list | while read -r line; do
        worktree_path=$(echo "$line" | awk '{print $1}')
        branch=$(echo "$line" | grep -o '\[.*\]' | tr -d '[]')

        if [ "$worktree_path" = "$PROJECT_DIR" ]; then
            echo -e "  ${GREEN}●${NC} main (primary worktree)"
            echo -e "    ${CYAN}$worktree_path${NC}"
        else
            task_name=$(basename "$worktree_path")
            echo -e "  ${YELLOW}●${NC} $task_name"
            echo -e "    Branch: ${CYAN}$branch${NC}"
            echo -e "    Path: ${CYAN}$worktree_path${NC}"
        fi
        echo ""
    done

    if git worktree list | grep -q "prunable"; then
        echo ""
        print_warning "Some worktrees are marked as 'prunable' (branch deleted)"
        print_info "Run 'cleanup-all' to remove them"
    fi
}

switch_session() {
    local task_name=$1
    local normalized_task_name
    local worktree_dir

    if [ -z "$task_name" ]; then
        print_error "Task name is required"
        echo "Usage: $0 switch <task-name>"
        exit 1
    fi

    normalized_task_name=$(normalize_task_name "$task_name")
    if [ -z "$normalized_task_name" ]; then
        print_error "Task name '$task_name' cannot be normalized into a valid worktree name"
        exit 1
    fi
    task_name="$normalized_task_name"

    if ! worktree_dir=$(find_session_dir "$task_name"); then
        print_error "Session '$task_name' does not exist"
        print_info "Available sessions:"
        list_sessions
        exit 1
    fi

    print_success "Switching to session: $task_name"
    print_info "Opening new shell in: $worktree_dir"
    create_repo_symlinks "$worktree_dir"
    echo ""
    print_info "Launch your agent from this worktree after the shell opens."
    echo ""

    cd "$worktree_dir"
    exec "$SHELL"
}

# Resolve a directory to its physical path; print the input unchanged when it
# does not exist (a stale worktree entry).
physical_path() {
    if [ -d "$1" ]; then
        (cd "$1" && pwd -P)
    else
        echo "$1"
    fi
}

# True when the path lies strictly inside WORKTREE_BASE.
is_under_worktree_base() {
    local path=$1
    local base base_phys path_phys
    base="${WORKTREE_BASE%/}"
    base_phys=$(physical_path "$base")
    path_phys=$(physical_path "$path")

    case "$path" in "$base"/?*) return 0 ;; esac
    case "$path_phys" in "$base_phys"/?*) return 0 ;; esac
    return 1
}

# True when the worktree has no uncommitted changes and no untracked files.
# A directory that Git cannot read counts as not clean.
worktree_is_clean() {
    local worktree_dir=$1
    local changes

    if [ ! -d "$worktree_dir" ]; then
        return 0
    fi
    if ! changes=$(git -C "$worktree_dir" status --porcelain 2>/dev/null); then
        return 1
    fi
    [ -z "$changes" ]
}

# The refs that count as integration targets: work contained in one of them
# is merged. A branch that is only pushed, or only contained in another
# feature branch, is not.
INTEGRATION_REFS=(main origin/main)

# True when every commit of the revision has a patch-equivalent commit on the
# given ref, as after a rebase merge ("git cherry" marks such commits "-").
# "git cherry" skips merge commits, whose conflict resolutions can carry
# changes of their own, so a revision with a merge commit of its own is left
# to the squash check, which compares the whole tree.
rev_is_rebase_merged() {
    local rev=$1
    local ref=$2
    local cherry merges

    merges=$(git -C "$PROJECT_DIR" rev-list --merges --count "$ref..$rev" 2>/dev/null) || return 1
    [ "$merges" -eq 0 ] || return 1
    cherry=$(git -C "$PROJECT_DIR" cherry "$ref" "$rev" 2>/dev/null) || return 1
    [ -n "$cherry" ] && ! grep -q '^+' <<< "$cherry"
}

# True when the revision was squash-merged into the given ref: its whole
# change, squashed onto its merge base, has the same patch as a commit on the
# ref. The squashed commit is a dangling object; no ref points at it.
rev_is_squash_merged() {
    local rev=$1
    local ref=$2
    local base squashed

    base=$(git -C "$PROJECT_DIR" merge-base "$ref" "$rev" 2>/dev/null) || return 1
    squashed=$(git -C "$PROJECT_DIR" commit-tree "$rev^{tree}" -p "$base" \
        -m "worktree-session squash probe" 2>/dev/null) || return 1
    [[ "$(git -C "$PROJECT_DIR" cherry "$ref" "$squashed" 2>/dev/null)" == "-"* ]]
}

# True when the revision is contained in an integration ref, or was
# rebase-merged or squash-merged into one.
rev_is_integrated() {
    local rev=$1
    local ref

    for ref in "${INTEGRATION_REFS[@]}"; do
        git -C "$PROJECT_DIR" rev-parse --verify --quiet "$ref^{commit}" >/dev/null 2>&1 || continue
        if git -C "$PROJECT_DIR" merge-base --is-ancestor "$rev" "$ref" 2>/dev/null ||
            rev_is_rebase_merged "$rev" "$ref" ||
            rev_is_squash_merged "$rev" "$ref"; then
            return 0
        fi
    done
    return 1
}

# True when the branch is absent or integrated into main or origin/main.
branch_is_merged() {
    local branch=$1

    if ! git -C "$PROJECT_DIR" show-ref --verify --quiet "refs/heads/$branch"; then
        return 0
    fi
    rev_is_integrated "refs/heads/$branch"
}

# Print the commit a worktree has checked out when its HEAD is detached;
# print nothing when a branch is checked out or the directory is gone.
detached_head() {
    local worktree_dir=$1

    [ -d "$worktree_dir" ] || return 0
    if ! git -C "$worktree_dir" symbolic-ref -q HEAD >/dev/null 2>&1; then
        git -C "$worktree_dir" rev-parse --verify --quiet HEAD 2>/dev/null || true
    fi
}

# Print the reasons why removing a worktree and its branch would lose work;
# print nothing when it loses none. The optional third argument is the
# worktree's detached HEAD commit, for a worktree whose directory is gone.
removal_blockers() {
    local worktree_dir=$1
    local branch=$2
    local head=${3:-}

    if ! worktree_is_clean "$worktree_dir"; then
        echo "worktree has uncommitted changes or untracked files: $worktree_dir"
    fi
    if [ -n "$branch" ] && ! branch_is_merged "$branch"; then
        echo "branch is not merged (or rebase- or squash-merged) into main or origin/main: $branch"
    fi
    # The session may have switched to another branch than the one it was
    # created with; that branch has to be merged as well.
    local current=""
    if [ -d "$worktree_dir" ]; then
        current=$(git -C "$worktree_dir" symbolic-ref -q --short HEAD 2>/dev/null) || current=""
    fi
    if [ -n "$current" ] && [ "$current" != "$branch" ] && ! branch_is_merged "$current"; then
        echo "checked-out branch is not merged (or rebase- or squash-merged) into main or origin/main: $current"
    fi
    [ -n "$head" ] || head=$(detached_head "$worktree_dir")
    if [ -n "$head" ] && ! rev_is_integrated "$head"; then
        echo "detached HEAD ${head:0:12} is not merged into main or origin/main: $worktree_dir"
    fi
}

cleanup_session() {
    local skip_confirm=false
    local force=false
    local task_name=""
    local normalized_task_name
    local worktree_dir
    local branch_name
    local blockers

    while [ $# -gt 0 ]; do
        case "$1" in
            -y|--yes) skip_confirm=true ;;
            -f|--force) force=true ;;
            -*)
                print_error "Unknown option: $1"
                echo "Usage: $0 cleanup [-y|--yes] [-f|--force] <task-name>"
                exit 1
                ;;
            *)
                if [ -n "$task_name" ]; then
                    print_error "cleanup takes exactly one task name (got '$task_name' and '$1')"
                    echo "Usage: $0 cleanup [-y|--yes] [-f|--force] <task-name>"
                    exit 1
                fi
                task_name=$1
                ;;
        esac
        shift
    done

    if [ -z "$task_name" ]; then
        print_error "Task name is required"
        echo "Usage: $0 cleanup [-y|--yes] [-f|--force] <task-name>"
        exit 1
    fi

    normalized_task_name=$(normalize_task_name "$task_name")
    if [ -z "$normalized_task_name" ]; then
        print_error "Task name '$task_name' cannot be normalized into a valid worktree name"
        exit 1
    fi
    task_name="$normalized_task_name"

    if ! worktree_dir=$(find_session_dir "$task_name"); then
        print_error "Session '$task_name' does not exist"
        exit 1
    fi

    branch_name="feature/$task_name"

    blockers=$(removal_blockers "$worktree_dir" "$branch_name")
    if [ -n "$blockers" ]; then
        if [ "$force" = false ]; then
            print_error "Refusing to clean up session '$task_name':"
            while IFS= read -r line; do echo "    $line"; done <<< "$blockers"
            print_info "Commit, push or merge the work first, or rerun with --force to discard it"
            exit 1
        fi
        print_warning "--force given; the following work will be lost:"
        while IFS= read -r line; do echo "    $line"; done <<< "$blockers"
    fi

    print_warning "This will remove the worktree and delete the branch!"
    print_info "Worktree: $worktree_dir"
    print_info "Branch: $branch_name"

    if [ "$skip_confirm" = false ]; then
        echo ""
        read -p "Are you sure? (y/N): " -n 1 -r
        echo

        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_info "Cleanup cancelled"
            exit 0
        fi
    fi

    cd "$PROJECT_DIR"

    local remove_args=(worktree remove)
    if [ "$force" = true ]; then
        remove_args+=(--force)
    fi

    if git "${remove_args[@]}" "$worktree_dir" 2>/dev/null; then
        print_success "Worktree removed"
    elif [ -d "$worktree_dir" ]; then
        print_error "Failed to remove worktree: $worktree_dir"
        print_info "The branch is kept. Run 'git worktree remove $worktree_dir' to see why."
        exit 1
    else
        print_warning "Failed to remove worktree (may already be gone)"
    fi

    # Without --force, removal_blockers has already confirmed that the branch
    # is merged (or rebase- or squash-merged) into main or origin/main.
    # git's own -d check knows only HEAD and the upstream, so -D deletes it here.
    if git branch -D "$branch_name" 2>/dev/null; then
        print_success "Branch deleted"
    else
        print_warning "Failed to delete branch (may already be deleted)"
    fi

    print_success "Session '$task_name' cleaned up"
}

cleanup_all() {
    local skip_confirm=false
    local force=false

    while [ $# -gt 0 ]; do
        case "$1" in
            -y|--yes) skip_confirm=true ;;
            -f|--force) force=true ;;
            *)
                print_error "Unknown option: $1"
                echo "Usage: $0 cleanup-all [-y|--yes] [-f|--force]"
                exit 1
                ;;
        esac
        shift
    done

    print_warning "This will remove ALL session worktrees under $WORKTREE_BASE and their branches!"
    if [ "$force" = false ]; then
        print_info "Sessions with uncommitted changes or an unmerged branch are kept (use --force to remove them)"
    fi

    if [ "$skip_confirm" = false ]; then
        echo ""
        read -p "Are you sure? (y/N): " -n 1 -r
        echo

        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_info "Cleanup cancelled"
            exit 0
        fi
    fi

    cd "$PROJECT_DIR"

    local primary_path worktree_path branch head detached line rest blockers
    local skipped=0
    local entries=()
    primary_path=""

    # Porcelain output: one "worktree <path>" line per entry, then
    # "HEAD <sha>" and "branch refs/heads/<name>" or "detached"; a blank line
    # ends the entry. The first entry is the primary.
    worktree_path=""
    branch=""
    head=""
    detached=false
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            "worktree "*)
                worktree_path=${line#worktree }
                ;;
            "HEAD "*)
                head=${line#HEAD }
                ;;
            "branch refs/heads/"*)
                branch=${line#branch refs/heads/}
                ;;
            "detached")
                detached=true
                ;;
            "")
                if [ -n "$worktree_path" ]; then
                    [ "$detached" = true ] || head=""
                    entries+=("$worktree_path"$'\t'"$branch"$'\t'"$head")
                fi
                worktree_path=""
                branch=""
                head=""
                detached=false
                ;;
        esac
    done < <(git worktree list --porcelain; echo)

    for line in "${entries[@]}"; do
        worktree_path=${line%%$'\t'*}
        rest=${line#*$'\t'}
        branch=${rest%%$'\t'*}
        head=${rest#*$'\t'}

        if [ -z "$primary_path" ]; then
            primary_path=$worktree_path
            continue
        fi

        if ! is_under_worktree_base "$worktree_path"; then
            print_info "Keeping (outside $WORKTREE_BASE): $worktree_path"
            continue
        fi

        blockers=$(removal_blockers "$worktree_path" "$branch" "$head")
        if [ -n "$blockers" ] && [ "$force" = false ]; then
            print_warning "Keeping: $worktree_path (${branch:-detached})"
            while IFS= read -r reason; do echo "    $reason"; done <<< "$blockers"
            skipped=$((skipped + 1))
            continue
        fi

        print_info "Removing: $worktree_path (${branch:-detached})"

        if [ "$force" = true ]; then
            git worktree remove --force "$worktree_path" 2>/dev/null && print_success "  Worktree removed"
        else
            git worktree remove "$worktree_path" 2>/dev/null && print_success "  Worktree removed"
        fi

        if [ -d "$worktree_path" ]; then
            print_warning "  Worktree still present; branch kept"
            skipped=$((skipped + 1))
            continue
        fi

        if [ -n "$branch" ] && git branch -D "$branch" 2>/dev/null; then
            print_success "  Branch deleted"
        fi
    done

    git worktree prune

    if [ "$skipped" -gt 0 ]; then
        print_warning "$skipped session(s) kept; rerun with --force to remove them"
    else
        print_success "All sessions cleaned up"
    fi
}

show_status() {
    echo -e "${CYAN}=== Current Worktree Status ===${NC}"
    echo ""

    if is_in_worktree; then
        local task_name
        task_name=$(get_current_worktree_name)
        print_info "You are in a session worktree"
        echo -e "  Session: ${YELLOW}$task_name${NC}"
        echo -e "  Path: ${CYAN}$(pwd)${NC}"
    else
        print_info "You are in the main repository (not a worktree)"
        echo -e "  Path: ${CYAN}$(pwd)${NC}"
    fi

    echo ""
    echo -e "${CYAN}=== Git Status ===${NC}"
    echo ""
    git status -sb

    echo ""
    echo -e "${CYAN}=== All Worktrees ===${NC}"
    echo ""
    git worktree list
}

main() {
    check_git_repo

    case "${1:-}" in
        start)
            shift
            start_session "$@"
            ;;
        list|ls)
            list_sessions
            ;;
        switch|sw)
            shift
            switch_session "$@"
            ;;
        cleanup|rm)
            shift
            cleanup_session "$@"
            ;;
        cleanup-all|clean)
            shift
            cleanup_all "$@"
            ;;
        status|st)
            show_status
            ;;
        help|--help|-h)
            show_help
            ;;
        "")
            print_error "No command specified"
            echo ""
            show_help
            exit 1
            ;;
        *)
            print_error "Unknown command: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"
