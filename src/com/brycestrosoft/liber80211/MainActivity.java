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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

/*
 * The single screen of the app: adapter state, live counters, capture control,
 * channel selection and the frame log.
 *
 * The USB work happens on background threads: probing the adapter bit-bangs its
 * EEPROM over thousands of control transfers, which would ANR the UI thread.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.brycestrosoft.liber80211.USB_PERMISSION";
    private static final int REQUEST_PERMISSIONS = 1;
    private static final long STATUS_INTERVAL_MS = 1000;

    // USB state
    private UsbManager _usb_manager;
    private volatile UsbDeviceConnection _conn;
    private volatile UsbInterface _intf_0;
    private UsbEndpoint _ep_rx;
    private UsbEndpoint _ep_tx;
    private volatile boolean _driver_started = false;

    // Capture state
    private volatile RxThread _rx;
    private volatile InjectServer _inject;
    private File _capture_dir;
    private OutputStreamWriter _osw_log;
    private long _last_rx_count = 0;

    // Views
    private TextView _text_state;
    private TextView _text_adapter;
    private TextView _text_mac;
    private TextView _text_channel;
    private TextView _text_rx_frames;
    private TextView _text_tx_frames;
    private TextView _text_socket;
    private TextView _text_channel_hint;
    private TextView _text_log;
    private Button _button_capture;
    private SeekBar _seek_channel;

    private BroadcastReceiver _usb_detached_receiver;
    private BroadcastReceiver _usb_permission_receiver;
    private LocationManager _loc_manager;
    private LocationListener _loc_listener;

    private final Handler _ui = new Handler(Looper.getMainLooper());
    private final Runnable _status_tick = new Runnable() {
        public void run() {
            update_status();
            _ui.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bind_views();

        _usb_manager = (UsbManager) getSystemService(Context.USB_SERVICE);

        _usb_detached_receiver = finish_on_usb_detached();
        registerReceiver(_usb_detached_receiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
        _usb_permission_receiver = usb_permission_receiver();
        registerReceiver(_usb_permission_receiver, new IntentFilter(ACTION_USB_PERMISSION));

        setup_capture_files();
        USBIO.set_log_file(_osw_log);
        USBIO.set_text_view(_text_log);

        request_runtime_permissions();

        UsbDevice device = adapter_from_intent_or_bus();
        if (device == null) {
            set_state(R.string.state_no_adapter);
            append_message("No USB Wi-Fi adapter found.\n"
                    + "Plug the adapter in through an OTG cable and relaunch WRAITH.\n");
            return;
        }
        _text_adapter.setText(describe_device(device));

        if (_usb_manager.hasPermission(device)) {
            start_driver(device);
        } else {
            set_state(R.string.state_permission);
            _usb_manager.requestPermission(device, permission_pending_intent());
        }

        _ui.post(_status_tick);
    }

    private void bind_views() {
        _text_state = (TextView) findViewById(R.id.text_state);
        _text_adapter = (TextView) findViewById(R.id.text_adapter);
        _text_mac = (TextView) findViewById(R.id.text_mac);
        _text_channel = (TextView) findViewById(R.id.text_channel);
        _text_rx_frames = (TextView) findViewById(R.id.text_rx_frames);
        _text_tx_frames = (TextView) findViewById(R.id.text_tx_frames);
        _text_socket = (TextView) findViewById(R.id.text_socket);
        _text_channel_hint = (TextView) findViewById(R.id.text_channel_hint);
        _text_log = (TextView) findViewById(R.id.text_log);
        _button_capture = (Button) findViewById(R.id.button_capture);
        _seek_channel = (SeekBar) findViewById(R.id.seek_channel);

        _button_capture.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Stats.receiving) {
                    stop_capture();
                } else {
                    start_capture();
                }
            }
        });
        findViewById(R.id.button_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                _text_log.setText("");
            }
        });

        _seek_channel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean from_user) {
                if (from_user) {
                    _text_channel_hint.setText("Channel: " + (progress + 1) + " (1-14)");
                }
            }

            public void onStartTrackingTouch(SeekBar bar) {
            }

            public void onStopTrackingTouch(SeekBar bar) {
                set_channel(bar.getProgress() + 1);
            }
        });
    }

    /*
     * Live panel: counters come from Stats so they match what the injection
     * socket reports to the shell tools.
     */
    private void update_status() {
        if (Stats.receiving) {
            long now = Stats.RX_FRAMES.get();
            long rate = now - _last_rx_count;
            _last_rx_count = now;
            set_state_text(getString(R.string.state_capturing) + " - " + rate + " frames/s");
            _button_capture.setText(R.string.action_stop_capture);
            _button_capture.setEnabled(true);
        } else if (_conn != null && _driver_started) {
            set_state(R.string.state_stopped);
            _button_capture.setText(R.string.action_start_capture);
            _button_capture.setEnabled(_ep_rx != null);
        }

        int channel = Stats.CHANNEL.get();
        _text_channel.setText(channel == 0 ? getString(R.string.value_unknown)
                : String.valueOf(channel));
        if (channel > 0) {
            _seek_channel.setProgress(channel - 1);
            _text_channel_hint.setText("Channel: " + channel + " (1-14)");
        }
        _text_rx_frames.setText(String.valueOf(Stats.RX_FRAMES.get()));
        _text_tx_frames.setText(String.valueOf(Stats.TX_FRAMES.get()));
        _text_socket.setText(Stats.injection_ready
                ? "127.0.0.1:" + InjectServer.PORT : getString(R.string.value_closed));

        if (DeviceProperties.mac != null) {
            String mac = format_mac(DeviceProperties.mac);
            if (!mac.equals(_text_mac.getText().toString())) {
                _text_mac.setText(mac);
            }
        }
    }

    /*
     * Retuning touches the radio over USB, so it cannot run on the UI thread.
     */
    private void set_channel(final int channel) {
        if (_conn == null) {
            append_message("Set a channel once the adapter is ready.\n");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                if (!USBIO.rtl8187_set_channel(_conn, channel)) {
                    append_message("Channel " + channel + " is not supported by this radio.\n");
                }
            }
        }, "wraith-channel").start();
    }

    private void start_capture() {
        if (_rx != null || _conn == null) {
            return;
        }
        _rx = spawn_rx_thread();
        Stats.receiving = true;
        append_message("Capture started.\n");
    }

    private void stop_capture() {
        if (_rx != null) {
            _rx.stopThread();
            _rx = null;
        }
        Stats.receiving = false;
    }

    private RxThread spawn_rx_thread() {
        RxThread rx = new RxThread(_conn, this, _ep_rx);
        rx.messages = _text_log;
        rx.fileMessages = _osw_log;
        Thread thread = new Thread(rx, "wraith-rx");
        thread.start();
        return rx;
    }

    private void setup_capture_files() {
        _capture_dir = pick_capture_dir();
        try {
            String log_name = "wraith_" + System.currentTimeMillis() + ".txt";
            File log_path = new File(_capture_dir, log_name);
            _osw_log = new OutputStreamWriter(new FileOutputStream(log_path));
            append_message("Logging to " + log_path.getAbsolutePath() + "\n");
        } catch (FileNotFoundException e) {
            append_message("Could not open the log file: " + e.getMessage() + "\n");
            _osw_log = null;
        }
        File pcap = new File(_capture_dir, "capture.pcap");
        if (PcapWriter.open(pcap)) {
            append_message("Frames written to " + pcap.getAbsolutePath() + "\n");
        } else {
            append_message("Could not open the pcap capture file.\n");
        }
    }

    /*
     * Prefers the public directory (/sdcard/wraith) so Termux can read the
     * captures, falls back to the app directory and finally internal storage.
     */
    private File pick_capture_dir() {
        File public_dir = new File(Environment.getExternalStorageDirectory(), "wraith");
        try {
            if ((public_dir.isDirectory() || public_dir.mkdirs())) {
                File probe = new File(public_dir, ".write_test");
                if (probe.createNewFile() || probe.exists()) {
                    probe.delete();
                    return public_dir;
                }
            }
        } catch (Exception e) {
            // fall through to the app-specific directory
        }
        File app_dir = getExternalFilesDir(null);
        return app_dir != null ? app_dir : getFilesDir();
    }

    /*
     * Opens the adapter and replays rtl8187_probe()/rtl8187_start() on a worker
     * thread, then publishes the injection socket.
     */
    private void start_driver(final UsbDevice device) {
        if (_driver_started) {
            return;
        }
        _driver_started = true;
        set_state(R.string.state_initialising);
        append_message("Adapter found, initialising driver...\n");

        new Thread(new Runnable() {
            public void run() {
                try {
                    UsbDeviceConnection conn = _usb_manager.openDevice(device);
                    if (conn == null) {
                        append_message("openDevice() returned null - USB permission was not granted.\n");
                        _driver_started = false;
                        return;
                    }
                    _conn = conn;

                    UsbInterface intf = device.getInterface(0);
                    if (!conn.claimInterface(intf, false) && !conn.claimInterface(intf, true)) {
                        append_message("claimInterface() failed, continuing anyway.\n");
                    }
                    _intf_0 = intf;

                    _ep_rx = pick_rx_endpoint(intf);
                    _ep_tx = pick_tx_endpoint(intf);
                    append_message("RX endpoint 0x" + Integer.toHexString(_ep_rx.getAddress())
                            + ", TX endpoint " + (_ep_tx == null ? "none"
                                    : "0x" + Integer.toHexString(_ep_tx.getAddress())) + "\n");

                    USBIO.do_log = true;
                    rtl8187_probe(conn, device);
                    USBIO.rtl8187_rfkill_poll(conn);
                    _rx = USBIO.rtl8187_start(conn, _ep_rx, MainActivity.this);
                    _rx.messages = _text_log;
                    _rx.fileMessages = _osw_log;
                    USBIO.rtl8187_add_interface(conn, DeviceProperties.mac);
                    // The RF init leaves the synthesiser at its reset value; tune to a
                    // known channel so the UI and the tools agree on where we are.
                    USBIO.rtl8187_set_channel(conn, 1);
                    Stats.receiving = true;

                    if (_ep_tx != null) {
                        _inject = new InjectServer(conn, _ep_tx);
                        new Thread(_inject, "wraith-inject").start();
                    } else {
                        append_message("No bulk OUT endpoint found, injection disabled.\n");
                    }

                    append_message("Monitor mode running. Frames to " + _capture_dir + "\n");
                } catch (Exception e) {
                    append_message(describe(e));
                    _driver_started = false;
                }
            }
        }, "wraith-init").start();
    }

    private int rtl8187_probe(UsbDeviceConnection conn, UsbDevice device) {
        // Each of these primes a cached value in DeviceProperties that later
        // steps depend on, and each mirrors a step of rtl8187_probe().
        USBIO.get_eeprom_width(conn);
        USBIO.perform_probe_black_magic_write_one(conn);
        USBIO.get_mac_address(conn);
        USBIO.setup_channels_part_one(conn);
        byte mystery_reg = USBIO.perform_probe_black_magic_read_write_one(conn);
        USBIO.get_asic_revision(conn);
        USBIO.perform_probe_black_magic_write_write_one(conn, mystery_reg);
        USBIO.get_hardware_revision(conn, device);
        USBIO.setup_channels_part_two(conn);
        // rtl8187_detect_rf() on the wire; the returned ops object is not used
        // yet because this port only supports the rtl8225z2 radios.
        USBIO.rtl8187_detect_rf(conn, device);
        USBIO.do_log = true;
        USBIO.config_rtl_8187_leds(conn);
        USBIO.rtl8187_rfkill_init(conn);
        return 0;
    }

    private static UsbEndpoint pick_rx_endpoint(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                return ep;
            }
        }
        return intf.getEndpoint(0);
    }

    private static UsbEndpoint pick_tx_endpoint(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    /*
     * Picks the RTL8187 out of the USB bus, falling back to whatever single
     * device is attached so unknown adapters are reported instead of ignored.
     */
    private UsbDevice adapter_from_intent_or_bus() {
        Intent intent = getIntent();
        if (intent != null) {
            UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                return device;
            }
        }
        UsbDevice fallback = null;
        Map<String, UsbDevice> devices = _usb_manager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            int vendor = device.getVendorId();
            int product = device.getProductId();
            if (vendor == 0x0bda && (product == 0x8187 || product == 0x8189
                    || product == 0x8197 || product == 0x8198)) {
                return device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        return fallback;
    }

    private PendingIntent permission_pending_intent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 31) {
            return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE);
        }
        return PendingIntent.getBroadcast(this, 0, intent, 0);
    }

    private void request_runtime_permissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            start_location_updates();
            return;
        }
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    && results[i] == PackageManager.PERMISSION_GRANTED) {
                start_location_updates();
            }
        }
    }

    /*
     * Writes a GPS-tagged line alongside the frames so a capture can be placed
     * on a map afterwards. Capture works without it, so failures are reported
     * and then ignored.
     */
    private void start_location_updates() {
        _loc_manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        _loc_listener = new LocationListener() {
            public void onLocationChanged(Location location) {
                String reading = System.currentTimeMillis() + "\tLOCATION\t"
                        + location.getTime() + "\t" + location.getLatitude() + "\t"
                        + location.getLongitude() + "\t"
                        + (location.hasAltitude() ? location.getAltitude() : "NA") + "\t"
                        + (location.hasAccuracy() ? location.getAccuracy() : "NA") + "\t"
                        + (location.hasSpeed() ? location.getSpeed() : "NA") + "\t"
                        + (location.hasBearing() ? location.getBearing() : "NA") + "\t"
                        + (location.getProvider() == null ? "NA" : location.getProvider()) + "\n";
                append_message(reading);
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            public void onProviderEnabled(String provider) {
            }

            public void onProviderDisabled(String provider) {
            }
        };

        try {
            if (_loc_manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                _loc_manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, _loc_listener);
            }
        } catch (SecurityException e) {
            append_message("Location permission missing, the capture will not be GPS-tagged.\n");
        } catch (Exception e) {
            append_message("Could not start location updates: " + describe(e));
        }
    }

    private BroadcastReceiver usb_permission_receiver() {
        return new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    return;
                }
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && device != null) {
                    start_driver(device);
                } else {
                    set_state(R.string.state_denied);
                    append_message("USB permission denied. Relaunch and allow access to the adapter.\n");
                }
            }
        };
    }

    private BroadcastReceiver finish_on_usb_detached() {
        return new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                    append_message("Adapter detached, closing.\n");
                    finish();
                }
            }
        };
    }

    private void set_state(int string_id) {
        set_state_text(getString(string_id));
    }

    private void set_state_text(final String text) {
        _ui.post(new Runnable() {
            public void run() {
                ((TextView) findViewById(R.id.text_state)).setText(text);
            }
        });
    }

    // Frames are appended on the RX thread, so hop to the UI thread.
    private void append_message(final String message) {
        _ui.post(new Runnable() {
            public void run() {
                _text_log.append(message);
                if (_text_log.getParent() instanceof View) {
                    View parent = (View) _text_log.getParent();
                    parent.scrollTo(0, _text_log.getBottom());
                }
            }
        });
        if (_osw_log != null && !message.isEmpty()) {
            try {
                _osw_log.write(System.currentTimeMillis() + "\t" + message);
                _osw_log.flush();
            } catch (IOException e) {
                // the log is best effort
            }
        }
    }

    private static String describe_device(UsbDevice device) {
        return String.format("0x%04x:0x%04x", device.getVendorId(), device.getProductId());
    }

    private static String format_mac(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }

    private static String describe(Exception e) {
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        for (StackTraceElement line : e.getStackTrace()) {
            sb.append("\n  at ").append(line);
        }
        return sb.append('\n').toString();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        _ui.removeCallbacks(_status_tick);
        super.onDestroy();
        stop_capture();
        if (_inject != null) {
            _inject.stop();
        }
        if (_conn != null) {
            if (_intf_0 != null) {
                _conn.releaseInterface(_intf_0);
            }
            _conn.close();
        }
        if (_osw_log != null) {
            try {
                _osw_log.flush();
                _osw_log.close();
            } catch (IOException e) {
                // nothing useful to do while shutting down
            }
        }
        try {
            unregisterReceiver(_usb_detached_receiver);
        } catch (IllegalArgumentException e) {
            // already unregistered
        }
        try {
            unregisterReceiver(_usb_permission_receiver);
        } catch (IllegalArgumentException e) {
            // already unregistered
        }
        if (_loc_manager != null && _loc_listener != null) {
            _loc_manager.removeUpdates(_loc_listener);
        }
        PcapWriter.close();
    }
}
