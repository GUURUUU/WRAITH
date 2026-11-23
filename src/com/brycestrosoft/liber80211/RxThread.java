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

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.widget.TextView;
import android.widget.Toast;


public class RxThread implements Runnable {
	private UsbDeviceConnection _conn;
	private Activity _parent_activity;
	private UsbEndpoint _ep;
	public boolean stop_thread = false;
	public TextView messages;
	public OutputStreamWriter fileMessages;
	private Set<String> _seen_macs;
	private Set<String> _seen_essids;

	private static final int BUFFER_SIZE = 2500;
	private static final int BULK_TIMEOUT_MS = 1000;
	public RxThread(UsbDeviceConnection conn, Activity parentActivity, UsbEndpoint ep) {
		_conn = conn;
		_parent_activity = parentActivity;
		_ep = ep;
		_seen_macs = new HashSet<String>();
		_seen_essids = new HashSet<String>(); 
	}
	public void run() {
		try {
			// Synchronous bulk reads are used rather than a ring of UsbRequests because
			// bulkTransfer() reports the exact number of bytes received, which we need in
			// order to write correctly sized frames to the pcap capture.
			byte[] buffer = new byte[BUFFER_SIZE];
			while (stop_thread == false) {
				int length = _conn.bulkTransfer(_ep, buffer, buffer.length, BULK_TIMEOUT_MS);
				if (length <= 0) {
					// timeout or error; keep polling unless we've been asked to stop
					continue;
				}

				byte[] data = Arrays.copyOf(buffer, length);
				PcapWriter.write(data, length);

				if (extract_frame_control(data).equals("8000")) {
					boolean show_log_on_screen = true;
					String beacon_bssid = extractBssid(data);
					String beacon_essid = extractEssid(data);
					write_message(String.format("BEACON\t%s\t%s.\n", beacon_bssid, beacon_essid), _parent_activity, show_log_on_screen);
				}
				else if (extract_frame_control(data).equals("4000")) {
					String probe_request_sender_MAC = extract_probe_request_sender_MAC(data);
					String probe_request_essid = extract_probe_request_essid(data);
					boolean show_log_on_screen = true;
					// uncomment if you want to restrict printing on screen to only new MACs/ESSIDs
					/*boolean showLogOnScreen = false;
					if (!_seen_macs.contains(probeSenderMac)) {
						showLogOnScreen = true;
						_seen_macs.add(probeSenderMac);
					}
					else if (!_seen_essids.contains(probeEssid)) {
						showLogOnScreen = true;
						_seen_essids.add(probeEssid);
					}*/
					write_message(String.format("PROBE REQUEST\t%s\t%s.\n", probe_request_sender_MAC, probe_request_essid),
							_parent_activity, show_log_on_screen);
				}
			}
			write_message("About to exit run()\n", _parent_activity);
		}
		catch (Exception e) {
			write_message(e.getClass().toString() + "\n" + e.getMessage() + "\n" + e.getLocalizedMessage() + 
     			   "\n" + Arrays.toString(e.getStackTrace()) + "\n", _parent_activity);
		}
	}
	
	private static String extract_frame_control(byte[] frame) {
		byte[] frame_control_bytes = Arrays.copyOfRange(frame, 0, 2);
		// TODO: we should probably reverse the endianness here and then also fix up the clients
		// of this method not to rely on wonky endianness.
		return byte_array_to_hex_string(frame_control_bytes);
	}
	
	private static String extract_destination_address(byte[] frame) {
		// Pull the destination MAC address out of a 802.11 frame
		// [x,y)
		byte[] dest_bytes = Arrays.copyOfRange(frame, 4, 10);
		return byte_array_to_hex_string(dest_bytes);
	}
	
	private static String extractBssid(byte[] frame) {
		byte[] bssid_bytes = Arrays.copyOfRange(frame, 16,22);
		return byte_array_to_hex_string(bssid_bytes);
	}
	
	private static String extractEssid(byte[] frame) throws UnsupportedEncodingException {
		int essid_length = (int)frame[37];
		byte[] essid_bytes = Arrays.copyOfRange(frame, 38, 38 + essid_length);
		return new String(essid_bytes, "US-ASCII");
		//return byteArrayToHexString(essidBytes);
	}
	
	private static String extract_probe_request_sender_MAC(byte[] frame) {
		byte[] mac_bytes = Arrays.copyOfRange(frame, 10, 16);
		return byte_array_to_hex_string(mac_bytes);
	}
	
	private static String extract_probe_request_essid(byte[] frame) throws UnsupportedEncodingException {
		int essid_length = (int)frame[25];
		if (essid_length != 0) {
			byte[] essid_bytes = Arrays.copyOfRange(frame, 26, 26 + essid_length);
			return new String(essid_bytes, "US-ASCII");
		}
		return "broadcast essid";
	}
	
	private static String byte_array_to_hex_string(byte[] arr) {
		StringBuilder sb = new StringBuilder();
		for (byte b: arr) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
	
	private void write_message(final String message, Activity parent_activity) {
		// if caller doesn't specify whether to log to screen, do it by default.
		write_message(message, parent_activity, true);
	}
	
	// Write to the messages textview
	private void write_message(final String message, Activity parent_activity, boolean show_log_on_screen) {
		if (show_log_on_screen) {
			parent_activity.runOnUiThread(new Runnable() {
				public void run() {
					messages.append(System.currentTimeMillis() + "\t" + message);
				}
			});
		}
		if (fileMessages != null) {
			try {
				fileMessages.write(System.currentTimeMillis() + "\t" + message);
				fileMessages.flush();
			} catch (IOException e) {
				// nothing useful to do here
			}
		}
	}
	
	public void stopThread() {
		stop_thread = true;
	}
}