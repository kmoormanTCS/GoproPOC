#!/usr/bin/env bash
# Vendors the TCS brand assets into the app's res/ tree.
#
# The licensed Geometr415 fonts live in exactly one tracked place — the
# tcs-brand repo — so the copies here are gitignored. Run this once after
# cloning, and again whenever the brand assets change.
#
#   ./scripts/sync-brand.sh [path-to-tcs-brand]
#
# With no argument it looks for a tcs-brand checkout next to this repo, then in
# the usual spots on a TCS dev Mac.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FONT_DIR="$REPO_ROOT/app/src/main/res/font"
IMG_DIR="$REPO_ROOT/app/src/main/res/drawable-nodpi"

find_brand() {
  if [[ $# -gt 0 && -n "${1:-}" ]]; then echo "$1"; return; fi
  for c in \
    "$REPO_ROOT/../tcs-brand" \
    "$HOME/Desktop/ClaudeTCSFolder/tcs-brand" \
    "$HOME/Desktop/dataapp/tcs-brand" \
    "$HOME/tcs-brand"
  do
    [[ -f "$c/brand.css" ]] && { echo "$c"; return; }
  done
  return 1
}

BRAND="$(find_brand "${1:-}")" || {
  echo "error: could not find a tcs-brand checkout." >&2
  echo "  clone it:  git clone https://github.com/thompsoncs/tcs-brand.git" >&2
  echo "  then run:  ./scripts/sync-brand.sh /path/to/tcs-brand" >&2
  exit 1
}

echo "Syncing brand assets from: $BRAND"
mkdir -p "$FONT_DIR" "$IMG_DIR"

# Android resource names must be lowercase with underscores.
cp "$BRAND/fonts/Geometr415-Black.ttf"  "$FONT_DIR/geometr415_black.ttf"
cp "$BRAND/fonts/Geometr415-Medium.ttf" "$FONT_DIR/geometr415_medium.ttf"
cp "$BRAND/fonts/Geometr415-Lite.ttf"   "$FONT_DIR/geometr415_lite.ttf"
cp "$BRAND/logo-horizontal.png"         "$IMG_DIR/tcs_logo_horizontal.png"

echo "Done:"
echo "  $FONT_DIR/geometr415_{black,medium,lite}.ttf"
echo "  $IMG_DIR/tcs_logo_horizontal.png"
echo
echo "Palette tokens are mirrored in app/src/main/res/values/colors.xml —"
echo "update them by hand if brand.css :root changes."
