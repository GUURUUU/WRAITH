/**
 * Copyright 2012 Bryce Thomas <bryce.m.thomas@gmail.com>
 *
 * liber80211 is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package com.brycestrosoft.liber80211;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Everything the app knows about what the radio is doing, in one place, so the
 * activity, the capture threads and the injection socket all report the same
 * numbers instead of keeping their own counters.
 */
public final class Stats {
    public static final AtomicLong RX_FRAMES = new AtomicLong();
    public static final AtomicLong TX_FRAMES = new AtomicLong();
    public static final AtomicLong BEACONS = new AtomicLong();
    public static final AtomicLong PROBE_REQUESTS = new AtomicLong();
    public static final AtomicInteger CHANNEL = new AtomicInteger(0);

    // Set once the driver has claimed the adapter and monitor mode is running.
    public static volatile boolean receiving = false;
    public static volatile boolean injection_ready = false;

    private Stats() {
    }

    public static void reset_counters() {
        RX_FRAMES.set(0);
        TX_FRAMES.set(0);
        BEACONS.set(0);
        PROBE_REQUESTS.set(0);
    }

    /*
     * Human readable summary, used by the activity's status panel and by the
     * INFO command on the injection socket, so both always agree.
     */
    public static String summary() {
        return "state: " + (receiving ? "capturing" : "idle") + "\n"
                + "channel: " + (CHANNEL.get() == 0 ? "unknown" : String.valueOf(CHANNEL.get())) + "\n"
                + "rx frames: " + RX_FRAMES.get() + "\n"
                + "tx frames: " + TX_FRAMES.get() + "\n"
                + "beacons: " + BEACONS.get() + "\n"
                + "probe requests: " + PROBE_REQUESTS.get() + "\n"
                + "injection socket: " + (injection_ready ? "127.0.0.1:" + InjectServer.PORT : "closed") + "\n";
    }
}
