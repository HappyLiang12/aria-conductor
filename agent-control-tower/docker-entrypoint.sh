#!/bin/sh
# Starts the backend after aliasing the platform key into DEEPSEEK_API_KEY only on a
# DeepSeek base URL: the phone's AR locator spends DEEPSEEK_API_KEY and must never send
# another provider's bearer to api.deepseek.com. The arguments are the java command.
set -eu
case "${LLM_BASE_URL:-}" in
    *api.deepseek.com*)
        if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -n "${LLM_API_KEY:-}" ]; then
            export DEEPSEEK_API_KEY="$LLM_API_KEY"
        fi
        ;;
esac
exec "$@"
