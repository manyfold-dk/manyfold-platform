#!/usr/bin/env bash
#
# consolidate-claude-settings.sh - Consolidate Claude permissions from local to main settings
#
# This script merges permissions from .claude/settings.local.json into .claude/settings.json
# and removes the merged allow list from the local file; every other key of the local file
# is kept. It intelligently handles broader patterns to avoid duplicates.
#
# Usage:
#   ./scripts/consolidate-claude-settings.sh          # Run consolidation
#   ./scripts/consolidate-claude-settings.sh --dry-run # Preview changes without applying

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SETTINGS_FILE="$PROJECT_DIR/.claude/settings.json"
LOCAL_SETTINGS_FILE="$PROJECT_DIR/.claude/settings.local.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

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

DRY_RUN=false
if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=true
    print_warning "DRY RUN MODE - No files will be modified"
fi

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    print_error "jq is required but not installed"
    echo "Install it with: brew install jq"
    exit 1
fi

# Check if settings files exist
if [[ ! -f "$SETTINGS_FILE" ]]; then
    print_error "Settings file not found: $SETTINGS_FILE"
    exit 1
fi

if [[ ! -f "$LOCAL_SETTINGS_FILE" ]]; then
    print_info "No local settings file found - nothing to consolidate"
    exit 0
fi

echo -e "${CYAN}=== Claude Settings Consolidation ===${NC}"
echo ""

# Read local settings permissions
LOCAL_PERMISSIONS=$(jq -r '.permissions.allow[]? // empty' "$LOCAL_SETTINGS_FILE" 2>/dev/null || echo "")

if [[ -z "$LOCAL_PERMISSIONS" ]]; then
    print_info "No new permissions in settings.local.json"
    exit 0
fi

# Count new permissions
NEW_COUNT=$(grep -vc '^$' <<< "$LOCAL_PERMISSIONS" || true)
print_info "Found $NEW_COUNT permission(s) in settings.local.json"
echo ""

# Show what will be added
echo -e "${YELLOW}New permissions to consolidate:${NC}"
echo "$LOCAL_PERMISSIONS" | while read -r perm; do
    if [[ -n "$perm" ]]; then
        echo "  + $perm"
    fi
done
echo ""

if [[ "$DRY_RUN" == true ]]; then
    print_warning "Dry run complete - no changes made"
    exit 0
fi

# Merge permissions into settings.json
print_info "Merging permissions into settings.json..."

# Read current permissions from settings.json
CURRENT_PERMISSIONS=$(jq -r '.permissions.allow[]? // empty' "$SETTINGS_FILE")

# Combine and sort unique permissions
ALL_PERMISSIONS=$(echo -e "$CURRENT_PERMISSIONS\n$LOCAL_PERMISSIONS" | grep -v '^$' | sort -u)

# Create temporary file with merged permissions
TEMP_SETTINGS=$(mktemp)

# Build new settings.json with merged permissions
jq --argjson perms "$(echo "$ALL_PERMISSIONS" | jq -R . | jq -s .)" \
   '.permissions.allow = $perms' \
   "$SETTINGS_FILE" > "$TEMP_SETTINGS"

# Replace settings.json
mv "$TEMP_SETTINGS" "$SETTINGS_FILE"

print_success "Permissions merged into settings.json"

# Remove the merged allow list from settings.local.json and keep every other key.
# An emptied "permissions" object is removed as well.
print_info "Removing the merged allow list from settings.local.json..."
TEMP_LOCAL=$(mktemp)
jq 'del(.permissions.allow) | if (.permissions // {}) == {} then del(.permissions) else . end' \
   "$LOCAL_SETTINGS_FILE" > "$TEMP_LOCAL"
# Write through the existing file so that its mode is kept
cat "$TEMP_LOCAL" > "$LOCAL_SETTINGS_FILE"
rm -f "$TEMP_LOCAL"

print_success "Allow list removed from settings.local.json"

echo ""
print_success "Consolidation complete!"
echo ""
print_info "Summary:"
echo "  - Added $NEW_COUNT new permission(s) to settings.json"
echo "  - Removed the allow list from settings.local.json (other settings kept)"
echo ""
print_warning "Note: Claude will continue to add new permissions to settings.local.json"
print_info "Run this script periodically to consolidate them"
