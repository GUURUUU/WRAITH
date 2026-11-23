/**
 * liber80211 is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package com.brycestrosoft.liber80211;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes monitor-mode frames to a libpcap file (DLT_IEEE802_11) so the capture
 * can be opened by tshark/tcpdump, including from Termux.
 */
public class PcapWriter {
	public static final int LINKTYPE_IEEE802_11 = 105;
	private static final int SNAPLEN = 65535;

	private static OutputStream out;
	private static final Object lock = new Object();

	public static boolean open(File file) {
		synchronized (lock) {
			close_locked();
			try {
				out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
				// libpcap global header, little endian magic
				write_le32(0xa1b2c3d4);
				write_le16(2); // major version
				write_le16(4); // minor version
				write_le32(0); // thiszone
				write_le32(0); // sigfigs
				write_le32(SNAPLEN);
				write_le32(LINKTYPE_IEEE802_11);
				out.flush();
				return true;
			} catch (IOException e) {
				out = null;
				return false;
			}
		}
	}

	public static void write(byte[] frame, int length) {
		synchronized (lock) {
			if (out == null || frame == null) {
				return;
			}
			int len = Math.min(length, frame.length);
			if (len <= 0) {
				return;
			}
			long now = System.currentTimeMillis();
			try {
				write_le32((int) (now / 1000L)); // ts_sec
				write_le32((int) ((now % 1000L) * 1000L)); // ts_usec
				write_le32(len); // incl_len
				write_le32(len); // orig_len
				out.write(frame, 0, len);
				out.flush();
			} catch (IOException e) {
				// keep going; losing a frame is better than dying in the RX thread
			}
		}
	}

	public static void close() {
		synchronized (lock) {
			close_locked();
		}
	}

	private static void close_locked() {
		if (out != null) {
			try {
				out.flush();
				out.close();
			} catch (IOException e) {
				// nothing useful to do here
			}
			out = null;
		}
	}

	private static void write_le32(int value) throws IOException {
		out.write(value & 0xff);
		out.write((value >> 8) & 0xff);
		out.write((value >> 16) & 0xff);
		out.write((value >> 24) & 0xff);
	}

	private static void write_le16(int value) throws IOException {
		out.write(value & 0xff);
		out.write((value >> 8) & 0xff);
	}
}
