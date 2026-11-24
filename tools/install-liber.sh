#!/data/data/com.termux/files/usr/bin/bash
#
# Installs the `liber` command for Termux: the wrapper, the Python client it
# calls, and a `liber80211` alias.
#
#   bash tools/install-liber.sh
#
# Run it from a checkout, or point LIBER80211_SRC at the checkout.

set -eu

: "${PREFIX:?run this inside Termux, PREFIX is not set}"
SRC="${LIBER80211_SRC:-$(cd "$(dirname "$0")" && pwd)}"
LIB_DIR="$PREFIX/lib/liber80211"
BIN_DIR="$PREFIX/bin"

[ -f "$SRC/liber" ] || { echo "cannot find the liber script in $SRC" >&2; exit 1; }
[ -f "$SRC/liber80211-inject.py" ] || { echo "cannot find liber80211-inject.py in $SRC" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || {
    echo "python3 is required: pkg install python" >&2
    exit 1
}

mkdir -p "$LIB_DIR" "$BIN_DIR"
install -m 755 "$SRC/liber" "$BIN_DIR/liber"
install -m 644 "$SRC/liber80211-inject.py" "$LIB_DIR/liber80211-inject.py"
ln -sf "$BIN_DIR/liber" "$BIN_DIR/liber80211"

echo "installed:"
echo "  $BIN_DIR/liber"
echo "  $BIN_DIR/liber80211 -> liber"
echo "  $LIB_DIR/liber80211-inject.py"
echo
echo "try: liber doctor"
