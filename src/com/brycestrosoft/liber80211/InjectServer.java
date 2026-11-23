/**
 * Copyright 2012 Bryce Thomas <bryce.m.thomas@gmail.com>
 *
 * Based on the Linux RTL8187 driver, which is:
 * Copyright 2007 Michael Wu <flamingice@sourmilk.net>
 * Copyright 2007 Andrea Merello <andreamrl@tiscali.it>
 *
 * liber80211 is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package com.brycestrosoft.liber80211;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

/*
 * The adapter is not a kernel network interface, so tools such as aireplay-ng
 * cannot open it with libpcap.  Instead the app exposes the transmit path on a
 * loopback TCP socket and the Termux side sends frames to it.
 *
 * Bound to 127.0.0.1 only: nothing off the device can reach it.
 *
 * Protocol, one request per message, replies are a single status byte unless
 * stated otherwise:
 *   1  TX      u16 big-endian length, then the raw 802.11 frame (no FCS)
 *              -> 0 transmitted, 1 rejected
 *   2  CHANNEL u8 channel, 1..14
 *              -> 0 ok, 1 invalid or unsupported
 *   3  PING    no payload
 *              -> 0
 *   4  STATUS  no payload
 *              -> u32 big-endian frames transmitted, u8 current channel
 */
public class InjectServer implements Runnable {
    public static final int PORT = 8765;

    private final UsbDeviceConnection _conn;
    private final UsbEndpoint _out;
    private ServerSocket _server;
    private volatile boolean _stop = false;
    private volatile long _frames_sent = 0;

    public InjectServer(UsbDeviceConnection conn, UsbEndpoint out) {
        _conn = conn;
        _out = out;
    }

    public void run() {
        try {
            _server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            USBIO.log("Injection socket listening on 127.0.0.1:" + PORT + "\n");
            while (!_stop) {
                Socket socket = _server.accept();
                new Thread(new Client(socket), "liber80211-inject-client").start();
            }
        } catch (Exception e) {
            if (!_stop) {
                USBIO.log("Injection socket stopped: " + e + "\n");
            }
        }
    }

    public void stop() {
        _stop = true;
        try {
            if (_server != null) {
                _server.close();
            }
        } catch (Exception e) {
            // nothing useful to do while shutting down
        }
    }

    private class Client implements Runnable {
        private final Socket _socket;

        Client(Socket socket) {
            _socket = socket;
        }

        public void run() {
            try {
                DataInputStream in = new DataInputStream(_socket.getInputStream());
                OutputStream out = _socket.getOutputStream();
                while (!_stop) {
                    int command = in.read();
                    if (command < 0) {
                        break;
                    } else if (command == 1) {
                        int length = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                        byte[] frame = new byte[length];
                        in.readFully(frame);
                        int sent = USBIO.rtl8187_tx(_conn, _out, frame, USBIO.RATE_1M);
                        out.write(sent == 12 + length ? 0 : 1);
                        if (sent == 12 + length) {
                            _frames_sent++;
                        }
                    } else if (command == 2) {
                        int channel = in.readUnsignedByte();
                        out.write(USBIO.rtl8187_set_channel(_conn, channel) ? 0 : 1);
                    } else if (command == 3) {
                        out.write(0);
                    } else if (command == 4) {
                        long sent = _frames_sent;
                        out.write(new byte[] { (byte) (sent >> 24), (byte) (sent >> 16),
                                (byte) (sent >> 8), (byte) sent,
                                (byte) USBIO.current_channel });
                    } else {
                        out.write(2);
                        break;
                    }
                    out.flush();
                }
                _socket.close();
            } catch (Exception e) {
                // a client hanging up mid-command is normal, nothing to report
                try {
                    _socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
