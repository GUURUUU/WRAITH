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

import java.nio.ByteBuffer;

import android.hardware.usb.UsbRequest;

public class BulkMessage {
	ByteBuffer _buff;
	
	public BulkMessage(int size) {
		// UsbRequest.queue() hands the buffer straight to the kernel driver, so it
		// must be a direct buffer on current Android releases.
		_buff = ByteBuffer.allocateDirect(size);
	}
	
	// queue the read, returning flag indicating whether it queued successfully
	public boolean queueRead(UsbRequest req) {
		req.setClientData(this);
		return req.queue(_buff, _buff.capacity());
	}
	
	public ByteBuffer getByteBuffer() {
		return _buff;
	}

	// Number of bytes the hardware actually delivered for this transfer. Android sets
	// the buffer's limit to the transferred length when the request completes; if it
	// did not, fall back to the full buffer.
	public int getLength() {
		int limit = _buff.limit();
		if (limit > 0 && limit <= _buff.capacity()) {
			return limit;
		}
		return _buff.capacity();
	}

	// Direct buffers have no backing array, so copy the received bytes out for parsing.
	public byte[] getBytes() {
		ByteBuffer copy = _buff.duplicate();
		copy.clear();
		byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}
}
