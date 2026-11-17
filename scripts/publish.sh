#!/bin/sh
# 把 H:/dev/xingyi-tv 推到 GitHub 并开启 Pages。
# 前提:docs/index.html 已就位;gh 已登录。
set -e
cd "$(dirname "$0")/.."

REPO=hillarybeasley8876-dot/xingyi-tv

git add -A
git commit -m "星仪 XINGYI 开源:宣传页 + README

Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>" || echo "nothing to commit"

if ! gh repo view "$REPO" >/dev/null 2>&1; then
  gh repo create "$REPO" --public --source=. --push -d "星仪 XINGYI — 住在电视里的智能体。开源的小米电视语音 harness。"
else
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$REPO.git"
  git push -u origin main
fi

# 开启 Pages:main 分支 /docs 目录
gh api -X POST "repos/$REPO/pages" -f "source[branch]=main" -f "source[path]=/docs" 2>/dev/null \
  || gh api -X PUT "repos/$REPO/pages" -f "source[branch]=main" -f "source[path]=/docs"

echo "Pages URL: https://hillarybeasley8876-dot.github.io/xingyi-tv/"
