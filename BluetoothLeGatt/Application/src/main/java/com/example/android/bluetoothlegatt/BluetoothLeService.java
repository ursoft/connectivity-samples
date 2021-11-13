/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.android.bluetoothlegatt;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.os.ParcelUuid;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.UUID;

import android.widget.Toast;
//import com.bipr.hr2vp.plugin.ChannelController;
import com.dsi.ant.AntService;
import com.dsi.ant.channel.AntChannel;
import com.dsi.ant.channel.AntChannelProvider;
import com.dsi.ant.channel.IAntChannelEventHandler;
import com.dsi.ant.channel.PredefinedNetwork;
import com.dsi.ant.message.ChannelType;
import com.dsi.ant.message.ChannelId;
import android.content.ComponentName;
import android.content.ServiceConnection;
//import java.rmi.RemoteException;
/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();
    public static BluetoothLeService serv = null;
    public Boolean pendingAutoZeroRequest = false;
    public Boolean pendingCalibrationRequest = false;
    public Boolean pendingManufacturerPowerPageAcknowledged = false;
//    public Boolean pendingManufacturerSpeedPageAcknowledged = false;
    public int pendingPowerManufacturerRequest = 0;
    public Boolean pendingSetParameterRequest = false;
  //  public Boolean pendingSpeedCapabilitiesRequest = false;
    //public int pendingSpeedManufacturerRequest = 0;

    public AntChannel mANTChannel;
    public AntChannelProvider mAntChannelProvider;
    public AntService mAntRadioService;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    long mLastCalories = 0;
    int mLastTime = 0; //в 1024-х долях секунды
    double mLastPower = -1.0;
    long mLastRxTime = 0;

    //cadence calculator
    long[] mCadTimes = new long[600]; //currentTimeMillis
    int[]  mCadRotations = new int[600];
    int mCadPointer = -1;
    private void updateCadence(int cadRotations) {
        mCadPointer++;
        int cadPointer = mCadPointer % 600;
        mCadTimes[cadPointer] = mLastRxTime;
        if(mCadTimes[cadPointer] == 0) mCadTimes[cadPointer] = 1;
        mCadRotations[cadPointer] = cadRotations;
    }
    long mLastCalcCadTime = 0;
    int mLastCalcCad = 0;
    public int currentCadence() {
        long t = System.currentTimeMillis();
        if(t - mLastCalcCadTime < 1000 && mLastCalcCad != 0)
            return mLastCalcCad;
        if(t - mLastRxTime > 5000)
            return 0;
        int cadPointer = mCadPointer;
        int maxRot = mCadRotations[cadPointer % 600];
        int minRot = maxRot;
        long timeDuration = 0;
        while(timeDuration < 60000) {
            cadPointer--;
            if(cadPointer < 0) cadPointer += 600;
            long tryTime = mCadTimes[cadPointer % 600];
            if(tryTime == 0 || t - tryTime > 61000) break;
            timeDuration = t - tryTime;
            minRot = mCadRotations[cadPointer % 600];
            if(maxRot < minRot) maxRot += 0x1000000;
            if(timeDuration > 15000 && maxRot - minRot > 20) break;
        }
        if(timeDuration == 0) mLastCalcCad = 0;
        else mLastCalcCad = (int)((60000.0 / timeDuration) * (maxRot - minRot) + 0.5);
        mLastCalcCadTime = t;
        return mLastCalcCad;
    }

        //коэффициент нелинейности нагрузки 1-25 Schwinn
    double[] extra_mult = { 0.60, 0.60, 0.60, 0.60, 0.60, 0.60, 0.60, 0.60, 0.60, 0.60, //1-10
        0.60, 0.60, 0.60, //11-13
        0.75, 0.91, 1.07, //14-16
        1.23, 1.39, 1.55, //17-19
        1.72, 1.88, 2.04, //20-22
        2.20, 2.36, 2.52  //23-25
    };
    private byte[] lastSchwinnData;
    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                if(data.length == 17 && data[0] == 17 && data[1] == 32 && data[2] == 0) {
                    lastSchwinnData = data.clone();
                    mLastRxTime = System.currentTimeMillis();
                    updateCadence((data[4] & 0xFF) | ((data[5] & 0xFF) << 8) | ((data[6] & 0xFF) << 16));
                    long calories = (data[10] & 0xFF) | ((data[11] & 0xFF) << 8) | ((data[12] & 0xFF) << 16) | ((data[13] & 0xFF) << 24) | ((long)(data[14] & 0xFF) << 32) | ((long)(data[15] & 0xFF) << 40);
                    int tim = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
                    if(mLastCalories == 0 || tim == mLastTime) {
                        mLastCalories = calories;
                        mLastTime = tim;
                    } else {
                        long dcalories = calories - mLastCalories;
                        mLastCalories = calories;
                        int dtime = tim - mLastTime;
                        mLastTime = tim;
                        if(dtime < 0) dtime += 65536;

                        SharedPreferences prefs = getApplicationContext().getSharedPreferences("prefs", Context.MODE_PRIVATE);
                        int em_idx = (int)data[16] - 1;
                        if (em_idx < 0) em_idx = 0;
                        if (em_idx > 24) em_idx = 24;
                        double mult = prefs.getInt("Cal2WattMult", 42) / 100.0 * extra_mult[em_idx];
                        double power = (double)dcalories / (double)dtime * mult;
                        if(mLastPower == -1.0 || Math.abs(mLastPower - power) < 100.0)
                            mLastPower = power;
                        else
                            mLastPower += (power - mLastPower) / 2.0;
                        if(mLastPower < 0) mLastPower = 1.0;
                        intent.putExtra(EXTRA_DATA, String.valueOf((int)mLastPower) + String.format(" %ds", tim/1024) + String.format(" %dc %d rpm", calories/256, currentCadence()));
                        openPowerChannel();
                        notifyConnectedBleClients();
                    }
                } else {
                    final StringBuilder stringBuilder = new StringBuilder(data.length);
                    for(byte byteChar : data)
                        stringBuilder.append(String.format("%02X ", byteChar));
                    intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
                }
            }
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    protected byte[] page10 = new byte[8];
    protected int cnt = 0, acc_power = 0;

    public interface ChannelChangedListener {
        void onAllowAddChannel(boolean z);

        void onChannelChanged(ChannelInfo channelInfo);
    }
    ChannelChangedListener mListener;
    public ChannelController powerChannelController;
    public ChannelController.ChannelBroadcastListener powerChannelListener;

    private void openPowerChannel() {
        if(this.mANTChannel != null) return;
        acc_power = 0;
        mLastPower = 0;
        try {
            this.mANTChannel = this.mAntChannelProvider.acquireChannel(this, PredefinedNetwork.ANT_PLUS);
            this.powerChannelListener = new ChannelController.ChannelBroadcastListener() {
                @Override
                public void onBroadcastChanged(ChannelInfo channelInfo) {
                    if (BluetoothLeService.serv.mListener != null) {
                        BluetoothLeService.serv.mListener.onChannelChanged(channelInfo);
                    }
                }
            };
            this.powerChannelController = new ChannelController(this.mANTChannel, true, 40000, this.powerChannelListener);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), e.toString(), Toast.LENGTH_SHORT).show();
        }
    }
    private ServiceConnection mAntRadioServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            BluetoothLeService.this.mAntRadioService = new AntService(iBinder);
            try {
                BluetoothLeService.this.mAntChannelProvider = BluetoothLeService.this.mAntRadioService.getChannelProvider();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName componentName) {
            BluetoothLeService.this.mAntChannelProvider = null;
            BluetoothLeService.this.mAntRadioService = null;
        }
    };
    private final IBinder mBinder = new LocalBinder();
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothGattServer mGattServer;
    private ArrayList<BluetoothDevice> mConnectedBleClients;

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        serv = this;
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        AntService.bindService(this, this.mAntRadioServiceConnection);

        initBleServer();
        return true;
    }

    private static UUID BLE_SERVICE_UUID_CYCLING_POWER = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb");
    private static UUID CHARACTERISTIC_POWER_MEASUREMENT = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb");
    private static UUID CHARACTERISTIC_POWER_FEATURE = UUID.fromString("00002a65-0000-1000-8000-00805f9b34fb");
    private static UUID CHARACTERISTIC_SENSOR_LOCATION = UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb");

    private static UUID BLE_SERVICE_UUID_CYCLING_SPD_CAD = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");
    private static UUID CHARACTERISTIC_CSC_MEASUREMENT = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    private static UUID CHARACTERISTIC_CSC_FEATURE = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb");

    private static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private void postStatusMessage(final String msg) {
        final Intent intent = new Intent(ACTION_DATA_AVAILABLE);
        intent.putExtra(EXTRA_DATA, msg);
        sendBroadcast(intent);
    }

    private void initBleServer() {
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        mConnectedBleClients = new ArrayList<BluetoothDevice>();

        BluetoothGattService service = new BluetoothGattService(BLE_SERVICE_UUID_CYCLING_POWER,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic pf = new BluetoothGattCharacteristic(CHARACTERISTIC_POWER_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattCharacteristic sl = new BluetoothGattCharacteristic(CHARACTERISTIC_SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        BluetoothGattCharacteristic pm = new BluetoothGattCharacteristic(CHARACTERISTIC_POWER_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor gd = new BluetoothGattDescriptor(CLIENT_CONFIG, BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ);
        pm.addDescriptor(gd);
        service.addCharacteristic(pm);
        service.addCharacteristic(pf);
        service.addCharacteristic(sl);

        mGattServer.addService(service);
    }
    private void shutdownBleServer() {
        //mHandler.removeCallbacks(mNotifyRunnable);
        if (mGattServer == null) return;
        mGattServer.close();
    }

    /* Storage and access to local characteristic data */
    private byte mLastCadLowByte = 0;
    private void notifyConnectedBleClients() {
        for (BluetoothDevice device : mConnectedBleClients) {
            BluetoothGattCharacteristic ch = mGattServer.getService(BLE_SERVICE_UUID_CYCLING_POWER)
                    .getCharacteristic(CHARACTERISTIC_POWER_MEASUREMENT);
            int power = (int)mLastPower;
            ch.setValue(new byte[] {0, 0, (byte)(power & 0xFF), (byte)((power >> 8) & 0xFF)});
            mGattServer.notifyCharacteristicChanged(device, ch, false);
            
            if(lastSchwinnData == null) return;

            byte cadLowByte = lastSchwinnData[4];
            if(mLastCadLowByte != cadLowByte) {
                mLastCadLowByte = cadLowByte;
                ch = mGattServer.getService(BLE_SERVICE_UUID_CYCLING_SPD_CAD)
                        .getCharacteristic(CHARACTERISTIC_CSC_MEASUREMENT);
                ch.setValue(new byte[] {2,                  // flags
                    lastSchwinnData[4], lastSchwinnData[5], //crank rev#
                    lastSchwinnData[8], lastSchwinnData[9]  //time
                });
                mGattServer.notifyCharacteristicChanged(device, ch, false);
            }
        }
    }
    /*
     * Callback handles all incoming requests from GATT clients.
     * From connections to read/write requests.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            //Log.i(TAG, "onConnectionStateChange "
            //        +DeviceProfile.getStatusDescription(status)+" "
            //        +DeviceProfile.getStateDescription(newState));

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectedBleClients.remove(device);
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
            if(BLE_SERVICE_UUID_CYCLING_POWER.equals(service.getUuid())) {
                service = new BluetoothGattService(BLE_SERVICE_UUID_CYCLING_SPD_CAD,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic cf = new BluetoothGattCharacteristic(CHARACTERISTIC_CSC_FEATURE,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
                BluetoothGattCharacteristic cm = new BluetoothGattCharacteristic(CHARACTERISTIC_CSC_MEASUREMENT,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);
                cm.addDescriptor(new BluetoothGattDescriptor(CLIENT_CONFIG, BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ));
                service.addCharacteristic(cm);
                service.addCharacteristic(cf);
                service.addCharacteristic(new BluetoothGattCharacteristic(CHARACTERISTIC_SENSOR_LOCATION,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ));

                mGattServer.addService(service);
            } else
                startBleAdvertising();
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
                Log.d(TAG, "Config descriptor read");
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        mConnectedBleClients.contains(device) ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    //Log.d(TAG, "Subscribe device to notifications: $device")
                    mConnectedBleClients.add(device);
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    //Log.d(TAG, "Unsubscribe device from notifications: $device")
                    mConnectedBleClients.remove(device);
                }
                if (responseNeeded) {
                    mGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null);
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.i(TAG, "onCharacteristicReadRequest " + characteristic.getUuid().toString());

            if (CHARACTERISTIC_POWER_FEATURE.equals(characteristic.getUuid())) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        new byte[] {0, 0, 1, 0});
            } else if (CHARACTERISTIC_CSC_FEATURE.equals(characteristic.getUuid())) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        new byte[] {2, 0});
            } else if (CHARACTERISTIC_SENSOR_LOCATION.equals(characteristic.getUuid())) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        new byte[] { 12 /* rear wheel */});
            }

            /*
             * Unless the characteristic supports WRITE_NO_RESPONSE,
             * always send a response back for any request.
             */
            /*mGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null);*/
        }
    };

    /*
     * Initialize the advertiser
     */
    private void startBleAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData adata = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(BLE_SERVICE_UUID_CYCLING_POWER))
                .addServiceUuid(new ParcelUuid(BLE_SERVICE_UUID_CYCLING_SPD_CAD))
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, adata, mAdvertiseCallback);
    }

    /*
     * Terminate the advertiser
     */
    private void stopBleAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /*
     * Callback handles events from the framework describing
     * if we were successful in starting the advertisement requests.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Peripheral Advertise Started.");
            postStatusMessage("GATT Server Ready");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Peripheral Advertise Failed: "+errorCode);
            postStatusMessage("GATT Server Error "+errorCode);
        }
    };

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        stopBleAdvertising();
        shutdownBleServer();
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        //if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : new byte[] { 0x00, 0x00 });
            mBluetoothGatt.writeDescriptor(descriptor);
        //}
    }
    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
