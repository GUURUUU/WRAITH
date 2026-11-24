# Injection socket protocol

The app listens on **127.0.0.1:8765** (TCP, loopback only). It exists because
the driver is a user space program: there is no monitor-mode network interface
for `libpcap` to open, so the transmit path is offered as a socket instead.

One request per message. Fields are big-endian. Replies are a single byte unless
the command says otherwise.

| Command | Request | Reply |
| --- | --- | --- |
| `1` TX | `u16` length, then the raw 802.11 frame | `0` accepted, `1` rejected |
| `2` CHANNEL | `u8` channel, 1-14 | `0` ok, `1` invalid |
| `3` PING | none | `0` |
| `4` STATUS | none | `u32` frames transmitted, `u8` channel |
| `5` INFO | none | `u16` length, then UTF-8 text |

Unknown commands reply `2` and the server closes the connection.

## Transmitting a frame

```
TX:   01 00 3f 80 00 00 00 00 ff ff ff ff ff ff 02 11 87 00 00 01 ...
       ^  ^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
       |  len=63                        frame bytes
       command

reply: 00
```

Rules the app enforces and the caller should respect:

- **No FCS.** The hardware appends the 4-byte checksum. Frames longer than 1700
  bytes or shorter than 10 are rejected.
- **802.11 header first.** The app prepends the RTL8187 transmit descriptor
  itself; nothing about the descriptor belongs on the wire from the caller's
  point of view.
- **One attempt.** The descriptor asks for a single transmission, no retries.
- **1 Mbit/s.** Management frames have to go at a basic rate.
- **Whatever the radio is tuned to.** There is no per-frame channel; send
  `CHANNEL` first.

## Reading the status

`INFO` returns the same text the app shows on screen, so a written tool behaves
like `wraith info`:

```
state: capturing
channel: 6
rx frames: 18234
tx frames: 47
beacons: 8112
probe requests: 344
injection socket: 127.0.0.1:8765
```

## Minimal client

```python
import socket, struct

def send_frame(frame, host="127.0.0.1", port=8765):
    with socket.create_connection((host, port), 5) as sock:
        sock.sendall(bytes([1]) + struct.pack(">H", len(frame)) + frame)
        return sock.recv(1) == b"\x00"
```

Connections are cheap: the app handles each one on its own thread, so a client
may either connect per frame or keep the connection open and pipeline commands.

## Why not a real interface

Creating a network interface needs a kernel driver, which on stock Android
means root and a module that matches the phone's kernel. This project avoids
that entirely, which is why monitor mode works on an unrooted phone — and why
`aireplay-ng` cannot be pointed at it.
