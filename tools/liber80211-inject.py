#!/data/data/com.termux/files/usr/bin/python3
"""Send 802.11 frames through liber80211 on Android.

The ALFA AWUS036H is driven in user space by the liber80211 app, so it never
appears as a network interface and tools like aireplay-ng cannot open it with
libpcap.  The app instead listens on 127.0.0.1:8765 and this script speaks its
protocol, so injection can be driven from a Termux prompt.

Usage:
  liber80211-inject.py channel 6
  liber80211-inject.py status
  liber80211-inject.py deauth AA:BB:CC:DD:EE:FF              # broadcast client
  liber80211-inject.py deauth AA:BB:CC:DD:EE:FF 11:22:33:44:55:66
  liber80211-inject.py beacon "Free WiFi"
  liber80211-inject.py raw c0000000ffffffffffffaabbccddeeffaabbccddeeff0007
  liber80211-inject.py replay capture.pcap --count 5
"""

import argparse
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 8765

CMD_TX = 1
CMD_CHANNEL = 2
CMD_PING = 3
CMD_STATUS = 4


class Adapter:
    def __init__(self, host=HOST, port=PORT, timeout=5.0):
        try:
            self.sock = socket.create_connection((host, port), timeout)
        except OSError as exc:
            sys.exit(f"cannot reach the app on {host}:{port} ({exc}).\n"
                     "Is liber80211 running with the adapter attached?")

    def _request(self, command, payload=b"", reply=1):
        self.sock.sendall(bytes([command]) + payload)
        data = b""
        while len(data) < reply:
            chunk = self.sock.recv(reply - len(data))
            if not chunk:
                raise OSError("app closed the connection")
            data += chunk
        return data

    def send(self, frame):
        """Returns True if the radio accepted the frame."""
        if not 10 <= len(frame) <= 1700:
            raise ValueError(f"frame length {len(frame)} is outside 10..1700")
        reply = self._request(CMD_TX, struct.pack(">H", len(frame)) + frame)
        if reply[0] == 2:
            raise OSError("app rejected the command")
        return reply[0] == 0

    def channel(self, number):
        return self._request(CMD_CHANNEL, bytes([number]))[0] == 0

    def status(self):
        sent, channel = struct.unpack(">IB", self._request(CMD_STATUS, reply=5))
        return sent, channel

    def close(self):
        self.sock.close()


def mac(text):
    parts = text.replace("-", ":").split(":")
    if len(parts) != 6:
        sys.exit(f"not a MAC address: {text}")
    return bytes(int(part, 16) for part in parts)


BROADCAST = b"\xff" * 6


def deauth(bssid, client=None, reason=7):
    """A deauth from the AP to a client, which is what drops a connection."""
    return (b"\xc0\x00" + b"\x00\x00"
            + (client or BROADCAST) + bssid + bssid
            + b"\x00\x00" + struct.pack("<H", reason))


def beacon(ssid, bssid, channel=1):
    ssid_bytes = ssid.encode("utf-8")
    if len(ssid_bytes) > 32:
        sys.exit("SSID is limited to 32 bytes")
    rates = bytes([0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24])
    body = (struct.pack("<QHH", 0, 100, 0x0401)
            + bytes([0, len(ssid_bytes)]) + ssid_bytes
            + bytes([1, len(rates)]) + rates
            + bytes([3, 1, channel]))
    return (b"\x80\x00" + b"\x00\x00" + BROADCAST + bssid + bssid
            + b"\x00\x00" + body)


def pcap_frames(path, strip_fcs):
    """Yields the 802.11 frames of a capture, skipping radiotap headers."""
    with open(path, "rb") as handle:
        data = handle.read()
    if len(data) < 24:
        sys.exit(f"{path} is too short to be a pcap file")
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
        endian = "<"
    elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        endian = ">"
    else:
        sys.exit(f"{path} is not a pcap file")
    linktype = struct.unpack(endian + "I", data[20:24])[0]
    offset = 24
    while offset + 16 <= len(data):
        _, _, incl, _ = struct.unpack(endian + "IIII", data[offset:offset + 16])
        offset += 16
        frame = data[offset:offset + incl]
        offset += incl
        if len(frame) < 10:
            continue
        if linktype == 127:          # radiotap
            frame = frame[struct.unpack("<H", frame[2:4])[0]:]
        elif linktype not in (105, 0):
            continue                  # not 802.11, nothing we can inject
        elif strip_fcs and len(frame) > 24:
            frame = frame[:-4]
        if len(frame) >= 10:
            yield frame


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=PORT)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("status", help="frames transmitted and current channel")
    chan = sub.add_parser("channel", help="retune the radio")
    chan.add_argument("number", type=int)

    deauth_cmd = sub.add_parser("deauth", help="send a deauth from an AP")
    deauth_cmd.add_argument("bssid")
    deauth_cmd.add_argument("client", nargs="?", default=None)
    deauth_cmd.add_argument("--reason", type=int, default=7)
    deauth_cmd.add_argument("--count", type=int, default=1)
    deauth_cmd.add_argument("--delay", type=float, default=0.1)

    beacon_cmd = sub.add_parser("beacon", help="broadcast a beacon for an SSID")
    beacon_cmd.add_argument("ssid")
    beacon_cmd.add_argument("--bssid", default="02:11:87:00:00:01")
    beacon_cmd.add_argument("--channel", type=int, default=1)
    beacon_cmd.add_argument("--count", type=int, default=1)
    beacon_cmd.add_argument("--delay", type=float, default=0.1)

    raw = sub.add_parser("raw", help="send a frame given as hex (or - for stdin)")
    raw.add_argument("hex", help="hexadecimal frame, or - to read it from stdin")
    raw.add_argument("--count", type=int, default=1)
    raw.add_argument("--delay", type=float, default=0.1)

    replay = sub.add_parser("replay", help="replay the frames of a pcap capture")
    replay.add_argument("file")
    replay.add_argument("--count", type=int, default=1, help="passes over the file")
    replay.add_argument("--delay", type=float, default=0.05)
    replay.add_argument("--keep-fcs", action="store_true",
                        help="keep the trailing checksum of link type 105 frames")

    args = parser.parse_args()
    adapter = Adapter(args.host, args.port)
    try:
        if args.command == "status":
            sent, channel = adapter.status()
            print(f"channel {channel or 'unknown'}, {sent} frames transmitted")
        elif args.command == "channel":
            if not adapter.channel(args.number):
                sys.exit(f"channel {args.number} rejected (1..14 on this radio)")
            print(f"tuned to channel {args.number}")
        elif args.command == "deauth":
            frame = deauth(mac(args.bssid), mac(args.client) if args.client else None,
                           args.reason)
            send_repeatedly(adapter, [frame], args.count, args.delay)
        elif args.command == "beacon":
            frame = beacon(args.ssid, mac(args.bssid), args.channel)
            send_repeatedly(adapter, [frame], args.count, args.delay)
        elif args.command == "raw":
            text = sys.stdin.read() if args.hex == "-" else args.hex
            send_repeatedly(adapter, [bytes.fromhex("".join(text.split()))],
                            args.count, args.delay)
        elif args.command == "replay":
            frames = list(pcap_frames(args.file, not args.keep_fcs))
            if not frames:
                sys.exit(f"no 802.11 frames found in {args.file}")
            send_repeatedly(adapter, frames, args.count, args.delay)
    finally:
        adapter.close()


def send_repeatedly(adapter, frames, passes, delay):
    sent = failed = 0
    for _ in range(passes):
        for frame in frames:
            if adapter.send(frame):
                sent += 1
            else:
                failed += 1
            time.sleep(delay)
    print(f"sent {sent} frame(s), {failed} rejected by the radio")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
