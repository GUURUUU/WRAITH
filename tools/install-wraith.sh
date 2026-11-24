#!/data/data/com.termux/files/usr/bin/bash
#
# Installs the `wraith` command for Termux: the wrapper, the Python client it
# calls, and `liber` / `liber80211` aliases for older notes.
#
#   bash tools/install-wraith.sh
#
# Run it from a checkout, or point WRAITH_SRC at the checkout.

set -eu

: "${PREFIX:?run this inside Termux, PREFIX is not set}"
SRC="${WRAITH_SRC:-$(cd "$(dirname "$0")" && pwd)}"
LIB_DIR="$PREFIX/lib/wraith"
BIN_DIR="$PREFIX/bin"

[ -f "$SRC/wraith" ] || { echo "cannot find the wraith script in $SRC" >&2; exit 1; }
[ -f "$SRC/wraith-inject.py" ] || { echo "cannot find wraith-inject.py in $SRC" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || {
    echo "python3 is required: pkg install python" >&2
    exit 1
}

mkdir -p "$LIB_DIR" "$BIN_DIR"
install -m 755 "$SRC/wraith" "$BIN_DIR/wraith"
install -m 644 "$SRC/wraith-inject.py" "$LIB_DIR/wraith-inject.py"
ln -sf "$BIN_DIR/wraith" "$BIN_DIR/liber"
ln -sf "$BIN_DIR/wraith" "$BIN_DIR/liber80211"

echo "installed:"
echo "  $BIN_DIR/wraith"
echo "  $BIN_DIR/liber       -> wraith"
echo "  $BIN_DIR/liber80211  -> wraith"
echo "  $LIB_DIR/wraith-inject.py"
echo
echo "try: wraith doctor"
