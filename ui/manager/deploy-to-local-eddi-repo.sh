#!/bin/bash
#
# SYNOPSIS
#    Build EDDI Manager and deploy to the EDDI backend resource directory.
#
# DESCRIPTION
#    1. Runs `npm run build` to produce the production bundle
#    2. Locates the new entry bundle (index-*.js / index-*.css)
#    3. Clears the legacy scripts/js and scripts/css locations
#    4. Copies the new assets folder in, then repoints the HTML shells at it
#    5. LAST, removes hashed assets the new build did not produce
#
#    Step 5 runs last on purpose: deleting stale files before the copy meant a
#    failed copy left the live shells referencing files already gone.
#
# NOTE ON CHUNK COUNT
#    The build is route-code-split, so dist/assets holds ~240 JS chunks plus the
#    font and Monaco files — around 700 files in total. That is normal and the
#    copy has always been wholesale; only ONE index-*.js and ONE index-*.css
#    exist, and those are still the only two names patched into the HTML shells.
#    Lazy chunks are referenced RELATIVELY ("./dashboard-<hash>.js") from the
#    entry chunk, which the backend serves from /assets/, so they resolve under
#    /assets/ no matter which SPA path the user is on.
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
echo -e "\n${CYAN}[1/5] Building EDDI Manager...${NC}"
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

echo -e "\n${CYAN}[2/5] New main assets:${NC}"
echo "  JS:  $NEW_JS_NAME"
echo "  CSS: $NEW_CSS_NAME"
echo -e "  Total assets: $TOTAL_ASSETS" -e "${DARK_GRAY}"

# ─── Step 3: Remove old files selectively ────────────────────────────────────
echo -e "\n${CYAN}[3/5] Clearing legacy asset locations...${NC}"

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


# ─── Step 4: Copy new assets + update manage.html ──────────────────────────
echo -e "\n${CYAN}[4/5] Deploying new assets...${NC}"

# Copy all files from dist/assets to destination
cp -f "$DIST_ASSETS"/* "$ASSETS_DIR/" 2>/dev/null || true
echo "  Copied all files into assets/"

# sed -i requires an explicit empty-string suffix on macOS (BSD sed).
# Using '' as the suffix means "no backup file".
SED_INPLACE=(sed -i '')

# Update manage.html references
# Replace the HTML references to either /scripts/js or /assets/ logic
"${SED_INPLACE[@]}" \
    -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
    -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
    "$MANAGE_HTML"

echo -e "\n  ${GREEN}Updated manage.html${NC}"

# Update welcome.html references (same bundles, different shell)
WELCOME_HTML="$RESOURCE_DIR/welcome.html"
if [[ -f "$WELCOME_HTML" ]]; then
    "${SED_INPLACE[@]}" \
        -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
        -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
        "$WELCOME_HTML"
    echo -e "  ${GREEN}Updated welcome.html${NC}"
fi

# Update workforce.html references (same bundles, different shell)
WORKFORCE_HTML="$RESOURCE_DIR/workforce.html"
if [[ -f "$WORKFORCE_HTML" ]]; then
    "${SED_INPLACE[@]}" \
        -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
        -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
        "$WORKFORCE_HTML"
    echo -e "  ${GREEN}Updated workforce.html${NC}"
fi

# Update index.html if it contains asset references (no-op when it is a redirect page)
if grep -q 'index-.*\.js\|index-.*\.css' "$INDEX_HTML" 2>/dev/null; then
    "${SED_INPLACE[@]}" \
        -e 's|src="/\(scripts/js\|assets\)/index-[^"]*\.js"|src="/assets/'"$NEW_JS_NAME"'"|g' \
        -e 's|href="/\(scripts/css\|assets\)/index-[^"]*\.css"|href="/assets/'"$NEW_CSS_NAME"'"|g' \
        "$INDEX_HTML"
    echo -e "  ${GREEN}Updated index.html${NC}"
fi
# ─── Step 5: Remove assets the new build did not produce ─────────────────────
#
# Deliberately LAST. Running it before the copy meant a failed `cp` or `sed`
# left the live HTML shells pointing at files this loop had already deleted —
# a broken UI and no rollback, from a deploy that never completed. Cleaning
# only after the new assets are in place and the shells reference them means
# the worst case of an interrupted deploy is some extra files on disk.
echo -e "\n${CYAN}[5/5] Removing assets the new build did not produce...${NC}"

# Clean hashed assets that the new build did not produce.
#
# This is a SET DIFFERENCE against the new dist, not a per-prefix sweep. The
# per-prefix version only deleted an old file when a same-prefixed new one
# existed, so a chunk that vanished between builds — a page renamed, a component
# removed, a lazy boundary moved — was never cleaned and silted up in the EDDI
# repo forever. That was survivable when the build emitted a handful of chunks.
# Route-level code splitting emits ~240, all content-hashed, so a stale set now
# accumulates fast enough to matter.
#
# Only files matching Vite's `name-<8charhash>.ext` shape are considered, so
# anything hand-placed in assets/ is left alone.
HASH_RE='^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$'

# Build the set of filenames the new build produced.
NEW_ASSET_NAMES=()
while IFS= read -r -d '' f; do
    NEW_ASSET_NAMES+=("$(basename "$f")")
done < <(find "$DIST_ASSETS" -maxdepth 1 -type f -print0)

is_in_new_build() {
    local needle="$1" name
    for name in "${NEW_ASSET_NAMES[@]}"; do
        [[ "$name" == "$needle" ]] && return 0
    done
    return 1
}

while IFS= read -r -d '' old; do
    oldname=$(basename "$old")
    [[ "$oldname" =~ $HASH_RE ]] || continue
    if ! is_in_new_build "$oldname"; then
        echo -e "  ${YELLOW}Removing stale asset $oldname${NC}"
        REMOVED_FILES+=("src/main/resources/META-INF/resources/assets/$oldname")
        rm -f "$old"
    fi
done < <(find "$ASSETS_DIR" -maxdepth 1 -type f -print0 2>/dev/null)

echo -e "\n${GREEN}[DONE] EDDI Manager deployed successfully!${NC}"
echo "  JS:  /assets/$NEW_JS_NAME"
echo -e "  CSS: /assets/$NEW_CSS_NAME\n"

# ─── Optional: Commit in EDDI repo ─────────────────────────────────────────
read -p "Commit these assets in the EDDI repo? [y/N] " answer
if [[ "$answer" =~ ^[Yy]$ ]]; then
    echo -e "\n${CYAN}Committing in EDDI repo...${NC}"

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
    git add "src/main/resources/META-INF/resources/welcome.html" 2>/dev/null || true
    git add "src/main/resources/META-INF/resources/workforce.html" 2>/dev/null || true
    git add "src/main/resources/META-INF/resources/index.html"

    # Stage the specific old files that were deleted
    # Guard with length check: bash 3.2 (macOS default) treats empty array
    # expansion "${arr[@]}" as unbound when set -u is active.
    if [[ ${#REMOVED_FILES[@]} -gt 0 ]]; then
        for removed in "${REMOVED_FILES[@]}"; do
            git add "$removed"
        done
    fi

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
