#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${ROOT_DIR}/codex-skills/yg-ai-backend-dev"

if [[ -n "${CODEX_SKILLS_DIR:-}" ]]; then
  TARGET_ROOT="${CODEX_SKILLS_DIR}"
elif [[ -n "${CODEX_HOME:-}" ]]; then
  TARGET_ROOT="${CODEX_HOME}/skills"
elif [[ -d "${HOME}/Library/Caches/JetBrains/IntelliJIdea2026.1/aia/codex/skills" ]]; then
  TARGET_ROOT="${HOME}/Library/Caches/JetBrains/IntelliJIdea2026.1/aia/codex/skills"
else
  TARGET_ROOT="${HOME}/.codex/skills"
fi

TARGET_DIR="${TARGET_ROOT}/yg-ai-backend-dev"

if [[ ! -f "${SOURCE_DIR}/SKILL.md" ]]; then
  echo "Missing skill source: ${SOURCE_DIR}/SKILL.md" >&2
  exit 1
fi

mkdir -p "${TARGET_ROOT}"
rsync -a --delete --exclude='.DS_Store' "${SOURCE_DIR}/" "${TARGET_DIR}/"

echo "Synced yg-ai-backend-dev to ${TARGET_DIR}"
