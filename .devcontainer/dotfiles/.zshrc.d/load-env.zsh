# Load environment variables from .env file
# This ensures GITHUB_PERSONAL_ACCESS_TOKEN and other secrets are available
if [ -f /workspace/.env ]; then
    set -a
    source /workspace/.env
    set +a
fi
