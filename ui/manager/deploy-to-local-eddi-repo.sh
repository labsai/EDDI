#!/bin/bash
#
# SYNOPSIS
#    Build EDDI Manager and deploy to the EDDI backend resource directory.
#
# DESCRIPTION
#    1. Runs `npm run build` to produce the production bundle
#    2. Cleans up old hashed assets from previous builds.
#    3. Copies the entire new assets folder into EDDI's assets/ directory.
#    4. Updates manage.html with the new hashed filenames
#
# USAGE
#    ./deploy-to-local-eddi-repo.sh
#    ./deploy-to-local-eddi-repo.sh /path/to/EDDI
#

set -euo pipefail

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default EDDI path (relative to script location)
DEFAULT_EDDI_PATH="$(cd "$SCRIPT_DIR/../EDDI" && pwd 2>/dev/null || echo "$SCRIPT_DIR/../EDDI")"
EDDI_PATH="${1:-$DEFAULT_EDDI_PATH}"

RESOURCE_DIR="$EDDI_PATH/src/main/resources/META-INF/resources"
ASSETS_DIR="$RESOURCE_DIR/assets"
SCRIPTS_JS="$RESOURCE_DIR/scripts/js"
SCRIPTS_CSS="$RESOURCE_DIR/scripts/css"
MANAGE_HTML="$RESOURCE_DIR/manage.html"
INDEX_HTML="$RESOURCE_DIR/index.html"

# Colors for output
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
DARK_GRAY='\033[0;90m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# ─── Validate paths ──────────────────────────────────────────────────────────
if [[ ! -f "$MANAGE_HTML" ]]; then
    echo -e "${RED}Error: manage.html not found at $MANAGE_HTML. Check EDDI path argument.${NC}" >&2
    echo "Usage: $0 [path/to/EDDI]"
    exit 1
fi

# ─── Step 1: Build ───────────────────────────────────────────────────────────
echo -e "\n${CYAN}[1/4] Building EDDI Manager...${NC}"
cd "$SCRIPT_DIR"
npm run build
if [[ $? -ne 0 ]]; then
    echo -e "${RED}Build failed!${NC}" >&2
    exit 1
fi
echo -e "  ${GREEN}Build succeeded.${NC}"

# ─── Step 2: Find new assets ────────────────────────────────────────────────
DIST_ASSETS="$SCRIPT_DIR/dist/assets"

# Find the main JS and CSS files (with hash)
NEW_JS=$(find "$DIST_ASSETS" -maxdepth 1 -name 'index-*.js' -type f | head -n 1)
NEW_CSS=$(find "$DIST_ASSETS" -maxdepth 1 -name 'index-*.css' -type f | head -n 1)

if [[ -z "$NEW_JS" ]] || [[ -z "$NEW_CSS" ]]; then
    echo -e "${RED}Error: Could not find index-*.js or index-*.css in dist/assets/${NC}" >&2
    exit 1
fi

NEW_JS_NAME=$(basename "$NEW_JS")
NEW_CSS_NAME=$(basename "$NEW_CSS")
TOTAL_ASSETS=$(find "$DIST_ASSETS" -maxdepth 1 -type f | wc -l)

echo -e "\n${CYAN}[2/4] New main assets:${NC}"
echo "  JS:  $NEW_JS_NAME"
echo "  CSS: $NEW_CSS_NAME"
echo -e "  Total assets: $TOTAL_ASSETS" -e "${DARK_GRAY}"

# ─── Step 3: Remove old files selectively ────────────────────────────────────
echo -e "\n${CYAN}[3/4] Cleaning old Manager assets (selectively)...${NC}"

REMOVED_FILES=()

# Cleanup legacy locations if any exist (from previous deployment structure)
if [[ -d "$SCRIPTS_JS" ]]; then
    while IFS= read -r -d '' file; do
        echo -e "  ${YELLOW}Removing legacy script $(basename "$file")${NC}"
        REMOVED_FILES+=("src/main/resources/META-INF/resources/scripts/js/$(basename "$file")")
        rm -f "$file"
    done < <(find "$SCRIPTS_JS" -maxdepth 1 -name 'index-*.js' -type f -print0 2>/dev/null)
fi

if [[ -d "$SCRIPTS_CSS" ]]; then
    while IFS= read -r -d '' file; do
        echo -e "  ${YELLOW}Removing legacy style $(basename "$file")${NC}"
        REMOVED_FILES+=("src/main/resources/META-INF/resources/scripts/css/$(basename "$file")")
        rm -f "$file"
    done < <(find "$SCRIPTS_CSS" -maxdepth 1 -name 'index-*.css' -type f -print0 2>/dev/null)
fi

# Ensure destination assets dir exists
mkdir -p "$ASSETS_DIR"

# Clean old versions of the currently generated files in assets/
# Match files with 8-character hashes: [prefix]-[hash].[ext]
while IFS= read -r -d '' f; do
    filename=$(basename "$f")
    # Check if filename matches pattern with 8-char hash
    if [[ "$filename" =~ ^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$ ]]; then
        prefix="${BASH_REMATCH[1]}"
        ext="${BASH_REMATCH[3]}"

        # Find matching old files in assets dir
        while IFS= read -r -d '' old; do
            oldname=$(basename "$old")
            # Check if old file also has 8-char hash pattern
            if [[ "$oldname" =~ ^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$ ]]; then
                if [[ "$oldname" != "$filename" ]]; then
                    echo -e "  ${YELLOW}Removing old asset $oldname${NC}"
                    REMOVED_FILES+=("src/main/resources/META-INF/resources/assets/$oldname")
                    rm -f "$old"
                fi
            fi
        done < <(find "$ASSETS_DIR" -maxdepth 1 -name "$prefix-*.$ext" -type f -print0 2>/dev/null)
    fi
done < <(find "$DIST_ASSETS" -maxdepth 1 -type f -print0)

# ─── Step 4: Copy new assets + update manage.html ──────────────────────────
echo -e "\n${CYAN}[4/4] Deploying new assets...${NC}"

# Copy all files from dist/assets to destination
cp -f "$DIST_ASSETS"/* "$ASSETS_DIR/" 2>/dev/null || true
echo "  Copied all files into assets/"

# Update manage.html references
# Replace the HTML references to either /scripts/js or /assets/ logic
sed -i \
    -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
    -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
    "$MANAGE_HTML"

echo -e "\n  ${GREEN}Updated manage.html${NC}"

# Update index.html if it contains asset references (no-op when it is a redirect page)
if grep -q 'index-.*\.js\|index-.*\.css' "$INDEX_HTML" 2>/dev/null; then
    sed -i \
        -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
        -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
        "$INDEX_HTML"
    echo -e "  ${GREEN}Updated index.html${NC}"
fi
echo -e "\n${GREEN}[DONE] EDDI Manager deployed successfully!${NC}"
echo "  JS:  /assets/$NEW_JS_NAME"
echo -e "  CSS: /assets/$NEW_CSS_NAME\n"

# ─── Step 5 (optional): Commit in EDDI repo ────────────────────────────────
read -p "Commit these assets in the EDDI repo? [y/N] " answer
if [[ "$answer" =~ ^[Yy]$ ]]; then
    echo -e "\n${CYAN}[5/5] Committing in EDDI repo...${NC}"

    # Get the latest Manager commit hash for the message
    MANAGER_HASH=$(git -C "$SCRIPT_DIR" log -1 --format="%h" 2>/dev/null || echo "")

    COMMIT_MSG="chore: update Manager UI assets"
    if [[ -n "$MANAGER_HASH" ]]; then
        COMMIT_MSG="chore: update Manager UI assets (Manager@$MANAGER_HASH)"
    fi

    cd "$EDDI_PATH"

    # Stage all newly added files from dist/assets into assets/
    while IFS= read -r -d '' f; do
        filename=$(basename "$f")
        git add "src/main/resources/META-INF/resources/assets/$filename"
    done < <(find "$DIST_ASSETS" -maxdepth 1 -type f -print0)

    git add "src/main/resources/META-INF/resources/manage.html"
    git add "src/main/resources/META-INF/resources/index.html"

    # Stage the specific old files that were deleted
    for removed in "${REMOVED_FILES[@]}"; do
        git add "$removed"
    done

    if git commit --no-verify -m "$COMMIT_MSG"; then
        echo -e "  ${GREEN}Committed: $COMMIT_MSG${NC}"
        if [[ -n "$MANAGER_HASH" ]]; then
            MANAGER_SUBJECT=$(git -C "$SCRIPT_DIR" log -1 --format="%s" 2>/dev/null || echo "")
            if [[ -n "$MANAGER_SUBJECT" ]]; then
                echo -e "  ${DARK_GRAY}Manager:   $MANAGER_SUBJECT${NC}"
            fi
        fi
    else
        echo -e "  ${YELLOW}Nothing to commit (files unchanged?)${NC}"
    fi
else
    echo -e "Skipped EDDI commit.${NC}"
fi
