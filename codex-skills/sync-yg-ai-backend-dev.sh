#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKILL_NAME="yg-ai-backend-dev"
PROJECT_SKILL_DIR="${ROOT_DIR}/codex-skills/${SKILL_NAME}"

if [[ -n "${CODEX_SKILLS_DIR:-}" ]]; then
  SYSTEM_SKILLS_ROOT="${CODEX_SKILLS_DIR}"
elif [[ -n "${CODEX_HOME:-}" ]]; then
  SYSTEM_SKILLS_ROOT="${CODEX_HOME}/skills"
elif [[ -d "${HOME}/Library/Caches/JetBrains/IntelliJIdea2026.1/aia/codex/skills" ]]; then
  SYSTEM_SKILLS_ROOT="${HOME}/Library/Caches/JetBrains/IntelliJIdea2026.1/aia/codex/skills"
else
  SYSTEM_SKILLS_ROOT="${HOME}/.codex/skills"
fi

SYSTEM_SKILL_DIR="${SYSTEM_SKILLS_ROOT}/${SKILL_NAME}"

if [[ ! -f "${PROJECT_SKILL_DIR}/SKILL.md" ]]; then
  echo "Missing project skill source: ${PROJECT_SKILL_DIR}/SKILL.md" >&2
  exit 1
fi

mkdir -p "${SYSTEM_SKILLS_ROOT}"
echo "Source project skill: ${PROJECT_SKILL_DIR}"
echo "Target system skill: ${SYSTEM_SKILL_DIR}"
rsync -a --delete --exclude='.DS_Store' "${PROJECT_SKILL_DIR}/" "${SYSTEM_SKILL_DIR}/"

echo "Synced ${SKILL_NAME} from project source to ${SYSTEM_SKILL_DIR}"
