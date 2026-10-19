#!/bin/bash
# Uploads the project to GitHub. Run from the shadowparent/ directory.
set -e

read -p "GitHub username: " GH_USER
read -p "GitHub repo name (e.g. shadowparent-dashboard): " GH_REPO
read -rsp "GitHub PAT (repo scope): " GH_TOKEN
echo

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    git init -b main
fi

git add -A
git -c user.name="deploy" -c user.email="deploy@local" commit -m "Deploy ShadowParent C2 dashboard"

REMOTE="https://${GH_USER}:${GH_TOKEN}@github.com/${GH_USER}/${GH_REPO}.git"
git remote remove origin 2>/dev/null || true
git remote add origin "$REMOTE"

echo "Pushing to github.com/${GH_USER}/${GH_REPO} ..."
git push -u origin main

echo
echo "Done. Now in Render:"
echo "  1. New+ -> Web Service -> pick this repo"
echo "  2. It reads render.yaml automatically"
echo "  3. Set ADMIN_PASS and API_KEY in the Environment tab"
echo "  4. Wait for the deploy, copy the https URL"
