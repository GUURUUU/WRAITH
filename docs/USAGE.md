# Using WRAITH

## First run

1. Plug the ALFA into the phone through the OTG cable.
2. Launch WRAITH. It asks for USB access to the card (allow it), and
   optionally for location and storage.
3. The status panel turns to `capturing` within a few seconds. The probe is
   slow by design: it bit-bangs the card's EEPROM over thousands of USB control
   transfers.

If the app reports **no supported USB adapter attached**, the card is not
enumerating. Check the OTG cable, and that the phone provides enough power — a
powered Y-cable fixes most of these cases.

## The screen

| Field | Meaning |
| --- | --- |
| Adapter | USB vendor:product of the card (`0x0bda:0x8187` for an AWUS036H) |
| MAC | the card's own hardware address, read from its EEPROM |
| Channel | current radio channel; the app tunes to 1 when it starts |
| RX frames | frames captured since launch |
| TX frames | frames injected since launch |
| Injection | the loopback socket the shell tools talk to |
| state line | `capturing - N frames/s`, or `capture stopped` |

**Stop capture / Start capture** stops and restarts the receive loop without
re-running the (slow) driver probe. **Clear log** only clears the screen; the
file keeps everything.

The channel slider retunes the radio when you release it. Changing channel also
changes what you receive: the log fills with that channel's beacons.

## Where the data goes

```
/sdcard/wraith/
  capture.pcap            libpcap, link type 105 (raw 802.11, no radiotap)
  wraith_<ms>.txt     one line per event, epoch-millisecond prefixed
```

The text log holds decoded beacons and probe requests, GPS fixes (when
permitted), and every injected frame. If `/sdcard/wraith` is not writable
the app falls back to its own external directory; the exact path is printed in
the log on startup.

## Termux

Install once from a checkout:

```bash
pkg install python ncurses-utils
bash tools/install-wraith.sh
wraith doctor
```

`wraith doctor` reports the capture directory, python, and whether the app's
socket answers.

### Reading captures

```bash
wraith capture ls
wraith capture info          # frame count, time window, BSSIDs, frame types
wraith capture pull          # copies pcap + recent logs to ~/WRAITH-captures
wraith log                   # newest log, last 25 lines
wraith log -f                # follow it
```

`capture info` needs no extra tools. For deeper analysis install `tshark`:

```bash
pkg install tshark
tshark -r /sdcard/wraith/capture.pcap
```

### Injecting

The app must be running with the card attached. All commands accept the usual
`--count` and `--delay` options.

```bash
wraith info                                  # state, channel, counters
wraith channel 6                             # retune first
wraith deauth AA:BB:CC:DD:EE:FF              # deauth every client of an AP
wraith deauth AA:BB:CC:DD:EE:FF 11:22:33:44:55:66 --count 10
wraith beacon "Free WiFi" --channel 6 --count 50
wraith raw c0000000ffffffffffffaabbccddeeffaabbccddeeff0007
wraith replay capture.pcap --count 3
```

`replay` understands captures written by this app (link type 105) and the usual
radiotap captures (link type 127); radiotap headers are stripped and the
trailing FCS is dropped unless you pass `--keep-fcs`.

Frames are transmitted at 1 Mbit/s. That is a basic rate every 802.11 client
must be able to receive; management frames sent at higher rates are frequently
ignored by the target.

### Writing your own tool

Anything that can open a TCP socket to `127.0.0.1:8765` can inject. The
protocol is four to six bytes of overhead per frame and is documented in
[PROTOCOL.md](PROTOCOL.md). `tools/wraith-inject.py` is a short, readable
implementation.

## Background use

The app keeps capturing while it is in the foreground. Android may freeze or
kill a backgrounded app, so keep it on screen while you work from Termux. If
you need the screen for something else, split-screen works.

## Getting the most out of the radio

- **Channel 1-13 are fine, 14 is Japan-only** and most clients will not answer
  there.
- **The card hears what its antenna hears.** The stock rubber duck is poor
  indoors; a 5 dBi omni makes a visible difference to the frame rate.
- **USB power matters.** A phone port that browns out will show up as the
  adapter detaching mid-capture.
- **Injection is one-way by design.** There is no association state, so a deauth
  sends the frame and moves on; it does not wait for an acknowledgement.
