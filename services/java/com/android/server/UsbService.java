/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.IUsbManager;
import android.hardware.UsbConstants;
import android.hardware.UsbDevice;
import android.hardware.UsbEndpoint;
import android.hardware.UsbInterface;
import android.hardware.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * <p>UsbService monitors for changes to USB state.
 */
class UsbService extends IUsbManager.Stub {
    private static final String TAG = UsbService.class.getSimpleName();
    private static final boolean LOG = false;

    private static final String USB_CONNECTED_MATCH =
            "DEVPATH=/devices/virtual/switch/usb_connected";
    private static final String USB_CONFIGURATION_MATCH =
            "DEVPATH=/devices/virtual/switch/usb_configuration";
    private static final String USB_FUNCTIONS_MATCH =
            "DEVPATH=/devices/virtual/usb_composite/";
    private static final String USB_CONNECTED_PATH =
            "/sys/class/switch/usb_connected/state";
    private static final String USB_CONFIGURATION_PATH =
            "/sys/class/switch/usb_configuration/state";
    private static final String USB_COMPOSITE_CLASS_PATH =
            "/sys/class/usb_composite";

    private static final int MSG_UPDATE = 0;

    // Delay for debouncing USB disconnects.
    // We often get rapid connect/disconnect events when enabling USB functions,
    // which need debouncing.
    private static final int UPDATE_DELAY = 1000;

    // current connected and configuration state
    private int mConnected;
    private int mConfiguration;

    // last broadcasted connected and configuration state
    private int mLastConnected = -1;
    private int mLastConfiguration = -1;

    // lists of enabled and disabled USB functions (for USB device mode)
    private final ArrayList<String> mEnabledFunctions = new ArrayList<String>();
    private final ArrayList<String> mDisabledFunctions = new ArrayList<String>();

    private final HashMap<String,UsbDevice> mDevices = new HashMap<String,UsbDevice>();

    private boolean mSystemReady;

    private final Context mContext;

    private final UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "USB UEVENT: " + event.toString());
            }

            synchronized (this) {
                String name = event.get("SWITCH_NAME");
                String state = event.get("SWITCH_STATE");
                if (name != null && state != null) {
                    try {
                        int intState = Integer.parseInt(state);
                        if ("usb_connected".equals(name)) {
                            mConnected = intState;
                            // trigger an Intent broadcast
                            if (mSystemReady) {
                                // debounce disconnects
                                update(mConnected == 0);
                            }
                        } else if ("usb_configuration".equals(name)) {
                            mConfiguration = intState;
                            // trigger an Intent broadcast
                            if (mSystemReady) {
                                update(mConnected == 0);
                            }
                        }
                    } catch (NumberFormatException e) {
                        Slog.e(TAG, "Could not parse switch state from event " + event);
                    }
                } else {
                    String function = event.get("FUNCTION");
                    String enabledStr = event.get("ENABLED");
                    if (function != null && enabledStr != null) {
                        // Note: we do not broadcast a change when a function is enabled or disabled.
                        // We just record the state change for the next broadcast.
                        boolean enabled = "1".equals(enabledStr);
                        if (enabled) {
                            if (!mEnabledFunctions.contains(function)) {
                                mEnabledFunctions.add(function);
                            }
                            mDisabledFunctions.remove(function);
                        } else {
                            if (!mDisabledFunctions.contains(function)) {
                                mDisabledFunctions.add(function);
                            }
                            mEnabledFunctions.remove(function);
                        }
                    }
                }
            }
        }
    };

    public UsbService(Context context) {
        mContext = context;
        init();  // set initial status

        if (mConfiguration >= 0) {
            mUEventObserver.startObserving(USB_CONNECTED_MATCH);
            mUEventObserver.startObserving(USB_CONFIGURATION_MATCH);
            mUEventObserver.startObserving(USB_FUNCTIONS_MATCH);
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

        mConfiguration = -1;
        try {
            FileReader file = new FileReader(USB_CONNECTED_PATH);
            int len = file.read(buffer, 0, 1024);
            file.close();
            mConnected = Integer.valueOf((new String(buffer, 0, len)).trim());

            file = new FileReader(USB_CONFIGURATION_PATH);
            len = file.read(buffer, 0, 1024);
            file.close();
            mConfiguration = Integer.valueOf((new String(buffer, 0, len)).trim());

        } catch (FileNotFoundException e) {
            Slog.i(TAG, "This kernel does not have USB configuration switch support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }
        if (mConfiguration < 0)
            return;

        try {
            File[] files = new File(USB_COMPOSITE_CLASS_PATH).listFiles();
            for (int i = 0; i < files.length; i++) {
                File file = new File(files[i], "enable");
                FileReader reader = new FileReader(file);
                int len = reader.read(buffer, 0, 1024);
                reader.close();
                int value = Integer.valueOf((new String(buffer, 0, len)).trim());
                String functionName = files[i].getName();
                if (value == 1) {
                    mEnabledFunctions.add(functionName);
                } else {
                    mDisabledFunctions.add(functionName);
                }
            }
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "This kernel does not have USB composite class support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }
    }

    // called from JNI in monitorUsbHostBus()
    private void usbDeviceAdded(String deviceName, int vendorID, int productID,
            int deviceClass, int deviceSubclass, int deviceProtocol,
            /* array of quintuples containing id, class, subclass, protocol
               and number of endpoints for each interface */
            int[] interfaceValues,
           /* array of quadruples containing address, attributes, max packet size
              and interval for each endpoint */
            int[] endpointValues) {

        // ignore hubs
        if (deviceClass == UsbConstants.USB_CLASS_HUB) {
            return;
        }

        synchronized (mDevices) {
            if (mDevices.get(deviceName) != null) {
                Log.w(TAG, "device already on mDevices list: " + deviceName);
                return;
            }

            int numInterfaces = interfaceValues.length / 5;
            Parcelable[] interfaces = new UsbInterface[numInterfaces];
            try {
                // repackage interfaceValues as an array of UsbInterface
                int intf, endp, ival = 0, eval = 0;
                boolean hasGoodInterface = false;
                for (intf = 0; intf < numInterfaces; intf++) {
                    int interfaceId = interfaceValues[ival++];
                    int interfaceClass = interfaceValues[ival++];
                    int interfaceSubclass = interfaceValues[ival++];
                    int interfaceProtocol = interfaceValues[ival++];
                    int numEndpoints = interfaceValues[ival++];

                    Parcelable[] endpoints = new UsbEndpoint[numEndpoints];
                    for (endp = 0; endp < numEndpoints; endp++) {
                        int address = endpointValues[eval++];
                        int attributes = endpointValues[eval++];
                        int maxPacketSize = endpointValues[eval++];
                        int interval = endpointValues[eval++];
                        endpoints[endp] = new UsbEndpoint(address, attributes,
                                maxPacketSize, interval);
                    }

                    if (interfaceClass != UsbConstants.USB_CLASS_HUB) {
                        hasGoodInterface = true;
                    }
                    interfaces[intf] = new UsbInterface(interfaceId, interfaceClass,
                            interfaceSubclass, interfaceProtocol, endpoints);
                }

                if (!hasGoodInterface) {
                    return;
                }
            } catch (Exception e) {
                // beware of index out of bound exceptions, which might happen if
                // a device does not set bNumEndpoints correctly
                Log.e(TAG, "error parsing USB descriptors", e);
                return;
            }

            UsbDevice device = new UsbDevice(deviceName, vendorID, productID,
                    deviceClass, deviceSubclass, deviceProtocol, interfaces);
            mDevices.put(deviceName, device);

            Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            intent.putExtra(UsbManager.EXTRA_DEVICE_NAME, deviceName);
            intent.putExtra(UsbManager.EXTRA_VENDOR_ID, vendorID);
            intent.putExtra(UsbManager.EXTRA_PRODUCT_ID, productID);
            intent.putExtra(UsbManager.EXTRA_DEVICE_CLASS, deviceClass);
            intent.putExtra(UsbManager.EXTRA_DEVICE_SUBCLASS, deviceSubclass);
            intent.putExtra(UsbManager.EXTRA_DEVICE_PROTOCOL, deviceProtocol);
            intent.putExtra(UsbManager.EXTRA_DEVICE, device);
            Log.d(TAG, "usbDeviceAdded, sending " + intent);
            mContext.sendBroadcast(intent);
        }
    }

    // called from JNI in monitorUsbHostBus()
    private void usbDeviceRemoved(String deviceName) {
        synchronized (mDevices) {
            UsbDevice device = mDevices.remove(deviceName);
            if (device != null) {
                Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED);
                intent.putExtra(UsbManager.EXTRA_DEVICE_NAME, deviceName);
                Log.d(TAG, "usbDeviceRemoved, sending " + intent);
                mContext.sendBroadcast(intent);
            }
        }
    }

    private void initHostSupport() {
        // Create a thread to call into native code to wait for USB host events.
        // This thread will call us back on usbDeviceAdded and usbDeviceRemoved.
        Runnable runnable = new Runnable() {
            public void run() {
                monitorUsbHostBus();
            }
        };
        new Thread(null, runnable, "UsbService host thread").start();
    }

    void systemReady() {
        synchronized (this) {
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_hasUsbHostSupport)) {
                // start monitoring for connected USB devices
                initHostSupport();
            }

            update(false);
            mSystemReady = true;
        }
    }

    private final void update(boolean delayed) {
        mHandler.removeMessages(MSG_UPDATE);
        mHandler.sendEmptyMessageDelayed(MSG_UPDATE, delayed ? UPDATE_DELAY : 0);
    }

    /* Returns a list of all currently attached USB devices */
    public void getDeviceList(Bundle devices) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_USB, null);
        synchronized (mDevices) {
            for (String name : mDevices.keySet()) {
                devices.putParcelable(name, mDevices.get(name));
            }
        }
    }

    public ParcelFileDescriptor openDevice(String deviceName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_USB, null);
        return nativeOpenDevice(deviceName);
    }

    private final Handler mHandler = new Handler() {
        private void addEnabledFunctions(Intent intent) {
            // include state of all USB functions in our extras
            for (int i = 0; i < mEnabledFunctions.size(); i++) {
                intent.putExtra(mEnabledFunctions.get(i), UsbManager.USB_FUNCTION_ENABLED);
            }
            for (int i = 0; i < mDisabledFunctions.size(); i++) {
                intent.putExtra(mDisabledFunctions.get(i), UsbManager.USB_FUNCTION_DISABLED);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE:
                    synchronized (this) {
                        if (mConnected != mLastConnected || mConfiguration != mLastConfiguration) {

                            final ContentResolver cr = mContext.getContentResolver();
                            if (Settings.Secure.getInt(cr,
                                    Settings.Secure.DEVICE_PROVISIONED, 0) == 0) {
                                Slog.i(TAG, "Device not provisioned, skipping USB broadcast");
                                return;
                            }

                            mLastConnected = mConnected;
                            mLastConfiguration = mConfiguration;

                            // send a sticky broadcast containing current USB state
                            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
                            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                            intent.putExtra(UsbManager.USB_CONNECTED, mConnected != 0);
                            intent.putExtra(UsbManager.USB_CONFIGURATION, mConfiguration);
                            addEnabledFunctions(intent);
                            mContext.sendStickyBroadcast(intent);
                        }
                    }
                    break;
            }
        }
    };

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceName);
}
