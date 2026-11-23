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
import java.util.Arrays;
import java.util.Map;

import com.brycestrosoft.liber80211.R;

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
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

/*
 * The primary Android activity that you seen when the app launches.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.brycestrosoft.liber80211.USB_PERMISSION";
    private static final int REQUEST_PERMISSIONS = 1;
    private File _capture_dir;

    private volatile RxThread _rx;
    private volatile UsbDeviceConnection _conn;
    private volatile UsbInterface _intf_0;
    private TextView _txt_general_info;
    private TextView _txt_rx;
    private OutputStreamWriter _osw_log;
    private BroadcastReceiver _usb_detached_receiver;
    private BroadcastReceiver _usb_permission_receiver;
    private UsbManager _usb_manager;
    private volatile boolean _driver_started = false;
    private LocationManager _loc_manager;
    private LocationListener _loc_listener;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // the textview that displays info about what was heard over the air.
        _txt_rx = (TextView) findViewById(R.id.textView2);
        _txt_rx.setMovementMethod(new ScrollingMovementMethod());
        _txt_general_info = (TextView) findViewById(R.id.textViewBla);

        _usb_manager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // setup a receiver to be notified when the Wi-Fi card is removed.
        _usb_detached_receiver = finish_on_usb_detached();
        registerReceiver(_usb_detached_receiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

        // setup a receiver for the response to our USB permission request.
        _usb_permission_receiver = usb_permission_receiver();
        registerReceiver(_usb_permission_receiver, new IntentFilter(ACTION_USB_PERMISSION));

        setup_log_file();

        // give USBIO an output stream and textview it can use for logging.
        USBIO.set_log_file(_osw_log);
        USBIO.set_text_view(_txt_rx);

        request_runtime_permissions();

        // When launched from the USB_DEVICE_ATTACHED intent we're handed the device.
        // When launched from the launcher we have to find it ourselves.
        Intent intent = getIntent();
        UsbDevice device = null;
        if (intent != null) {
            device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (device == null) {
            device = find_attached_adapter();
        }

        if (device == null) {
            append_message("No USB Wi-Fi adapter found.\nPlug in the adapter via OTG and relaunch liber80211.\n");
            _txt_general_info.append("No supported USB device attached.\n");
            return;
        }

        _txt_general_info.append("Vendor ID: " + device.getVendorId() + "\n");
        _txt_general_info.append("Product ID: " + device.getProductId() + "\n");

        if (!_usb_manager.hasPermission(device)) {
            append_message("Waiting for USB permission...\n");
            PendingIntent pi;
            if (Build.VERSION.SDK_INT >= 31) {
                pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_MUTABLE);
            } else {
                pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            }
            _usb_manager.requestPermission(device, pi);
        } else {
            start_driver(device);
        }
    }

    /*
     * Picks the RTL8187 device out of the USB bus. Falls back to whatever single
     * device is attached so that unknown vendor/product ids are reported rather
     * than silently ignored.
     */
    private UsbDevice find_attached_adapter() {
        Map<String, UsbDevice> devices = _usb_manager.getDeviceList();
        UsbDevice fallback = null;
        for (UsbDevice device : devices.values()) {
            int vendor = device.getVendorId();
            int product = device.getProductId();
            if (vendor == 3034 && product == 33159) { // 0x0bda:0x8187 RTL8187
                return device;
            }
            if (vendor == 0x050d && product == 0x705e) { // RTL8187B
                return device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        return fallback;
    }

    /*
     * Prefers a public directory (/sdcard/liber80211) so the capture can be read by
     * Termux and other apps. Falls back to the app-specific external directory, then
     * to internal storage if the storage permission was not granted.
     */
    private File pick_capture_dir() {
        File public_dir = new File(Environment.getExternalStorageDirectory(), "liber80211");
        try {
            if (public_dir.isDirectory() || public_dir.mkdirs()) {
                File probe = new File(public_dir, ".write_test");
                if (probe.createNewFile() || probe.exists()) {
                    probe.delete();
                    return public_dir;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        File app_dir = getExternalFilesDir(null);
        if (app_dir != null) {
            return app_dir;
        }
        return getFilesDir();
    }

    private void setup_log_file() {
        _capture_dir = pick_capture_dir();
        try {
            String log_file_name = "liber80211_" + String.valueOf(System.currentTimeMillis()) + ".txt";
            File log_file_path = new File(_capture_dir, log_file_name);
            FileOutputStream log_file = new FileOutputStream(log_file_path);
            _osw_log = new OutputStreamWriter(log_file);
            _txt_general_info.append("Logging to: " + log_file_path.getAbsolutePath() + "\n");
        } catch (FileNotFoundException e) {
            _txt_general_info.append("Could not open log file: " + e.getMessage() + "\n");
            _osw_log = null;
        }
        File pcap = new File(_capture_dir, "capture.pcap");
        if (PcapWriter.open(pcap)) {
            _txt_general_info.append("Frames written to: " + pcap.getAbsolutePath() + "\n");
        } else {
            _txt_general_info.append("Could not open pcap capture file.\n");
        }
    }

    /*
     * Opens the device and replays rtl8187_probe()/rtl8187_start() on a worker thread.
     * The probe bit-bangs the EEPROM over control transfers and takes several
     * seconds, which is far too long for the UI thread on modern Android.
     */
    private void start_driver(final UsbDevice device) {
        if (_driver_started) {
            return;
        }
        _driver_started = true;
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
                    if (!conn.claimInterface(intf, false)) {
                        // Force-claim detaches any kernel driver that got bound to the interface.
                        append_message("claimInterface() failed, retrying with force...\n");
                        if (!conn.claimInterface(intf, true)) {
                            append_message("claimInterface(force) also failed.\n");
                        } else {
                            append_message("claimInterface(force) succeeded.\n");
                        }
                    }
                    _intf_0 = intf;

                    UsbEndpoint ep = pick_rx_endpoint(intf);
                    append_message("RX endpoint: address=0x" + Integer.toHexString(ep.getAddress())
                            + " type=" + ep.getType() + " maxPacket=" + ep.getMaxPacketSize() + "\n");

                    USBIO.do_log = true;
                    /*** THIS IS WHERE WE GET DOWN TO BUSINESS ON THE WIRE. ***/
                    // rtl8187_probe() here is meant to behave (on the wire) like rtl8187_probe() in the Linux driver.
                    rtl8187_probe(conn, device);
                    // rtl8187_rfkill_poll() here is meant to behave (on the wire) like rtl8187_rfkill_poll() in the Linux driver.
                    USBIO.rtl8187_rfkill_poll(conn); // seems to be first "_ops" function called after probe phase.
                    USBIO.do_log = true;
                    // rtl8187_start() here is meant to behave (on the wire) like rtl8187_start() in the Linux driver.
                    _rx = USBIO.rtl8187_start(conn, ep, MainActivity.this);
                    USBIO.do_log = true;
                    // rtl8187_add_interface() here is meant to behave (on the wire) like rtl8187_add_interface() in the Linux driver.
                    USBIO.rtl8187_add_interface(conn, DeviceProperties.mac);

                    show_device_info(device);
                    append_message("Monitor mode started. Listening for frames...\n");
                } catch (Exception e) {
                    append_message(describe(e));
                }
            }
        }, "liber80211-init").start();
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

    private void show_device_info(final UsbDevice device) {
        final String info = "eeprom width is " + DeviceProperties.eeprom_width + "\n"
                + String.format("MAC is %02X:%02X:%02X:%02X:%02X:%02X\n", DeviceProperties.mac[0],
                        DeviceProperties.mac[1], DeviceProperties.mac[2], DeviceProperties.mac[3],
                        DeviceProperties.mac[4], DeviceProperties.mac[5])
                + "Interface count: " + device.getInterfaceCount() + "\n"
                + "Interface 1 endpoint count: " + device.getInterface(0).getEndpointCount() + "\n";
        runOnUiThread(new Runnable() {
            public void run() {
                _txt_general_info.append(info);
            }
        });
    }

    private void request_runtime_permissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            start_location_updates();
        } else {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                start_location_updates();
            }
        }
    }

    private void start_location_updates() {
        _loc_manager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        _loc_listener = new LocationListener() {
            public void onLocationChanged(Location location) {
                String time = String.valueOf(location.getTime());
                String lat = String.valueOf(location.getLatitude());
                String lon = String.valueOf(location.getLongitude());
                String alt = "NA";
                if (location.hasAltitude()) {
                    alt = String.valueOf(location.getAltitude());
                }
                String acc = "NA";
                if (location.hasAccuracy()) {
                    acc = String.valueOf(location.getAccuracy());
                }
                String speed = "NA";
                if (location.hasSpeed()) {
                    speed = String.valueOf(location.getSpeed());
                }
                String bearing = "NA";
                if (location.hasBearing()) {
                    bearing = String.valueOf(location.getBearing());
                }
                String provider = "NA";
                if (location.getProvider() != null) {
                    provider = location.getProvider();
                }
                String loc_reading_format = "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s.\n";
                String reading = System.currentTimeMillis() + "\t"
                        + String.format(loc_reading_format, time, lat, lon, alt, acc, speed, bearing, provider);
                _txt_rx.append(reading);
                write_log(reading);
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {}

            public void onProviderEnabled(String provider) {}

            public void onProviderDisabled(String provider) {}
        };

        try {
            if (_loc_manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                _loc_manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, _loc_listener);
            }
        } catch (SecurityException e) {
            append_message("Location permission missing, capture will not be GPS-tagged.\n");
        } catch (Exception e) {
            append_message("Could not start location updates: " + e.getMessage() + "\n");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.menu_about:
                intent = new Intent(this, AboutActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (_rx != null) {
            _rx.stopThread();
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
                e.printStackTrace();
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    private int rtl8187_probe(UsbDeviceConnection conn, UsbDevice device) {
        // we (ab)use this to prime the DeviceProperties eeprom width cached value for other methods.
        USBIO.get_eeprom_width(conn);
        USBIO.perform_probe_black_magic_write_one(conn);
        // another (ab)use to prime DeviceProperties, this time mac address.
        USBIO.get_mac_address(conn);
        USBIO.setup_channels_part_one(conn);
        byte mysteryReg = USBIO.perform_probe_black_magic_read_write_one(conn);
        // another (ab)use, this time asic revision priming.
        USBIO.get_asic_revision(conn);
        USBIO.perform_probe_black_magic_write_write_one(conn, mysteryReg);
        // more priming (ab)use.
        USBIO.get_hardware_revision(conn, device);
        USBIO.setup_channels_part_two(conn);
        // linux driver does some GPIO settings stuff here, but doesn't look like it applies to my AWUS 036H
        // TODO: at the moment we don't make use of the return value to rtl818xRfOps here,
        // but will need to if we want to support other cards in future.

        // meant to behave (on the wire) like rtl8187_detect_rf() in the Linux Driver.
        rtl818xRfOps ops = USBIO.rtl8187_detect_rf(conn, device);
        USBIO.do_log = true;

        // meant to behave (on the wire) like the conditional lines in #ifdef CONFIG_RTL8187_LEDS
        // in the linux driver.
        USBIO.config_rtl_8187_leds(conn);
        // meant to behave (on the wire) like rtl8187_rfkill_init() in the Linux driver.
        USBIO.rtl8187_rfkill_init(conn);
        return 0;
    }

    private BroadcastReceiver usb_permission_receiver() {
        return new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    return;
                }
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    start_driver(device);
                } else {
                    append_message("USB permission denied. Relaunch the app and allow access to the adapter.\n");
                }
            }
        };
    }

    /*
     * Sets up the activity to call finish() when the USB device is detached.
     * The returned BroadcastReceiver would typically be unregistered in the activity's onDestroy().
     * @return the BroadcastReceiver listening for the UsbManager.ACTION_USB_DEVICE_DETACHED event.
     */
    private BroadcastReceiver finish_on_usb_detached() {
        // setup a receiver to be notified when the Wi-Fi card is removed.
        BroadcastReceiver usb_detached_receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // TODO: at the moment this assumes whatever was detached was *our* USB device.
                // Might break with a hub and multiple devices.
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        finish();
                    }
                }
            }
        };
        return usb_detached_receiver;
    }

    private static String describe(Exception e) {
        return e.getClass().toString() + "\n" + e.getMessage() + "\n" + e.getLocalizedMessage()
                + "\n" + Arrays.toString(e.getStackTrace()) + "\n";
    }

    private void append_message(final String message) {
        write_log(message);
        runOnUiThread(new Runnable() {
            public void run() {
                _txt_rx.append(message);
            }
        });
    }

    private void write_log(String message) {
        if (_osw_log == null) {
            return;
        }
        try {
            _osw_log.write(System.currentTimeMillis() + "\t" + message);
            _osw_log.flush();
        } catch (IOException e) {
            // nothing useful to do here
        }
    }
}
