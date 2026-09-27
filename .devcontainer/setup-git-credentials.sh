#!/usr/bin/env bash
#
# Configure git credentials from environment variable
# This script is run automatically when the devcontainer starts
#

set -e

# Check if GITHUB_PERSONAL_ACCESS_TOKEN is set
if [ -z "${GITHUB_PERSONAL_ACCESS_TOKEN}" ]; then
    echo "⚠️  GITHUB_PERSONAL_ACCESS_TOKEN not set in environment"
    echo "   Git push/pull to private repositories will require manual authentication"
    exit 0
fi

# Get GitHub username from git remote or use a default
GITHUB_USERNAME="$(git config user.name 2>/dev/null || echo "git")"
if git remote get-url origin &>/dev/null; then
    # Try to extract username from remote URL
    EXTRACTED_USER=$(git remote get-url origin 2>/dev/null | sed -n 's/.*github.com[:/]\([^/]*\)\/.*/\1/p' || echo "")
    if [ -n "${EXTRACTED_USER}" ]; then
        GITHUB_USERNAME="${EXTRACTED_USER}"
    fi
fi

echo "ℹ️  Configuring git credentials for GitHub..."
echo "   Username: ${GITHUB_USERNAME}"

# Configure git to use credential helper
git config --global credential.helper store

# Create .git-credentials file with the token
mkdir -p ~/.git-credentials
cat > ~/.git-credentials/github << EOF
https://${GITHUB_USERNAME}:${GITHUB_PERSONAL_ACCESS_TOKEN}@github.com
EOF
chmod 600 ~/.git-credentials/github

# Configure git to use the credentials file
git config --global credential.helper "store --file ~/.git-credentials/github"

echo "✅ Git credentials configured successfully"
echo "   You can now push/pull from private repositories without entering credentials"
