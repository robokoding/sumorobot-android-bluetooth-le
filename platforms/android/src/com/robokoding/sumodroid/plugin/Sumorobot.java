package com.robokoding.sumodroid.plugin;

import java.util.UUID;
import java.util.ArrayList;

import android.util.Log;
import android.content.Intent;
import android.os.Environment;
import android.app.AlertDialog;
import android.hardware.Sensor;
import android.content.Context;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.content.DialogInterface;
import android.bluetooth.BluetoothGatt;
import android.content.BroadcastReceiver;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.hardware.SensorEventListener;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattCharacteristic;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;

/**
 * This class compiles Arduino code and sends it to the Sumorobot.
 */
public class Sumorobot extends CordovaPlugin implements SensorEventListener {
    /* app tag for log messages */
    private static final String TAG = "Sumorobot";
    /* orientation stuff */
    private float mRoll = 0.0f;
    private float mPitch = 0.0f;
    private float[] mLastAccels;
    private float[] mLastMagFields;
    private float mRollOffset = 0.0f;
    private float mPitchOffset = 0.0f;
    private String currentDirection = "stop";
    private float[] mOrientation = new float[4];
    private float[] mRotationMatrix = new float[16];
    private boolean isOrientationMovementActivated = false;
    private static final float ORIENTATION_ROLL_CHANGE_ANGLE = 10;
    private static final float ORIENTATION_PITCH_CHANGE_ANGLE = 10;
    /* bluetooth stuff */
    private BluetoothGatt mBluetoothGatt;
    private static String incomingData = "";
    private BluetoothAdapter mBluetoothAdapter;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int STATE_DISCONNECTED = 0;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothGattCharacteristic characteristicTX;
    private BluetoothGattCharacteristic characteristicRX;
    private static ArrayList<BluetoothDevice> mBluetoothDevices;
    private static String sumorobotAddress = "98:D3:31:B2:F4:A1";
    private final static UUID HM_RX_TX = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private final static UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (BluetoothGattService gattService : mBluetoothGatt.getServices()) {
                    /* get characteristic when UUID matches RX/TX UUID */
                    characteristicTX = gattService.getCharacteristic(HM_RX_TX);
                    characteristicRX = gattService.getCharacteristic(HM_RX_TX);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dataUpdate(characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            dataUpdate(characteristic);
        }
    };

    private void dataUpdate(final BluetoothGattCharacteristic characteristic) {
        /* for all other profiles, writes the data formatted in HEX */
        final byte[] data = characteristic.getValue();
        //Log.d(TAG, "data" + characteristic.getValue());

        if (data != null && data.length > 0) {
            /* getting cut off when longer, need to push on new line, 0A */
            String received = String.format("%s", new String(data));
            /* when the data contains a response message */
            if (incomingData.contains("true")) {
                webView.sendJavascript("app.sumorobotResponse(true)");
                Log.d(TAG, "received bluetooth data: " + incomingData);
                incomingData = "";
            }
            else if (incomingData.contains("false")) {
                webView.sendJavascript("app.sumorobotResponse(false)");
                Log.d(TAG, "received bluetooth data: " + incomingData);
                incomingData = "";
            } else {
                incomingData += received;
            }
        }
    }

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
    private boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (sumorobotAddress != null && address.equals(sumorobotAddress) && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                mBluetoothGatt = device.connectGatt(cordova.getActivity(), false, mGattCallback);
                sumorobotAddress = address;
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
        mBluetoothGatt = device.connectGatt(cordova.getActivity(), false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        sumorobotAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    private void sendData(String commands) {
        Log.d(TAG, "sending commands: " + commands);

        try {
            int start = 0;
            while (true) {
                String packet = "";
                if (commands.length() >= start + 20)
                    packet = commands.substring(start, start + 20);
                else
                    packet = commands.substring(start, commands.length());
                characteristicTX.setValue(packet);
                writeCharacteristic(characteristicTX);
                start += 20;
                if (start >= commands.length())
                    break;
                /* a small delay before sending next packet */
                Thread.sleep(1);
            }
            setCharacteristicNotification(characteristicRX, true);
        } catch (Exception e) {
            Log.d(TAG, "sending commands error: " + e.getMessage());
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    private void disconnect() {
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
    private void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Write to a given char
     * @param characteristic The characteristic to write to
     */
    private void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(characteristic);
    }   
    
    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    private void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        if (HM_RX_TX.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(TAG, "unable to obtain a BluetoothAdapter");
                throw new Exception("failed to obtain bluetooth adapter");
            }

            mBluetoothDevices = new ArrayList<BluetoothDevice>();
            /* initialize bluetooth connection */
            Log.d(TAG, "initializing bluetooth");
            /* when bluetooth is off */
            if (mBluetoothAdapter.isEnabled() == false) {
                /* turn on bluetooth */
                Log.d(TAG, "requesting user to turn on the bluetooth");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                cordova.getActivity().startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
            /* register sensor listener */
            registerSensorListener();
        } catch (Exception e) {
            Log.e(TAG, "bluetooth initialization error: " + e.getMessage());
        }
    }

    /**
     * Registers listeners for accelerometer and magnometer sensor
     */
    private void registerSensorListener() {
        Log.d(TAG, "registered sensors listeners");
        SensorManager sensorManager = (SensorManager) cordova.getActivity().getSystemService(Context.SENSOR_SERVICE);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
    }

    /**
     * Set accelerometer data
     * @param event {@link SensorEvent}
     */
    private void accel(SensorEvent event) {
        if (mLastAccels == null) {
            mLastAccels = new float[3];
        }
 
        System.arraycopy(event.values, 0, mLastAccels, 0, 3);
 
        /*if (m_lastMagFields != null) {
            computeOrientation();
        }*/
    }

    /**
     * Set magnometer data
     * @param event {@link SensorEvent}
     */
    private void mag(SensorEvent event) {
        if (mLastMagFields == null) {
            mLastMagFields = new float[3];
        }

        System.arraycopy(event.values, 0, mLastMagFields, 0, 3);

        if (mLastAccels != null) {
            computeOrientation();
        }
    }

    /**
     * Computes orientation of device
     */
    private void computeOrientation() {
        if (SensorManager.getRotationMatrix(mRotationMatrix, null, mLastAccels, mLastMagFields)) {
            SensorManager.getOrientation(mRotationMatrix, mOrientation); 
            /* 1 radian = 57.2957795 degrees */
            /* [0] : yaw, rotation around z axis
             * [1] : pitch, rotation around x axis
             * [2] : roll, rotation around y axis */
            mPitch = mOrientation[1] * 57.2957795f;
            mRoll = mOrientation[2] * 57.2957795f;

            sendCommandOnDeviceOrientation( mPitch - mPitchOffset, mRoll - mRollOffset );
        }
    }

    /**
     * Sends a drive command depending on the current device orientation
     * @param aPitch    {@link Float} device pitch in degree
     * @param aRoll     {@link Float} device roll in degree
     */
    private synchronized void sendCommandOnDeviceOrientation(float aPitch, float aRoll) {
        if (mConnectionState == STATE_CONNECTED && isOrientationMovementActivated) {
            String id =  Long.toString((long) Math.floor(Math.random() * 9000000000L) + 1000000000L);
            if (Math.abs(aPitch) > ORIENTATION_PITCH_CHANGE_ANGLE) {
                /* check if to drive left or right */
                if( aPitch > ORIENTATION_PITCH_CHANGE_ANGLE) {
                    Log.d(TAG, "left");
                    if (currentDirection != "left") {
                        sendData("{\"cmd\":\"left\",\"arg\":\"\",\"id\":\""+id+"\"}\n");
                        currentDirection = "left";
                    }
                } else {
                    Log.d(TAG, "right");
                    if (currentDirection != "right") {
                        sendData("{\"cmd\":\"right\",\"arg\":\"\",\"id\":\""+id+"\"}\n");
                        currentDirection = "right";
                    }
                }
            } else if (Math.abs(aRoll) > ORIENTATION_ROLL_CHANGE_ANGLE) {
                /* check if to drive forward or backward */
                if (aRoll > ORIENTATION_ROLL_CHANGE_ANGLE) {
                    Log.d(TAG, "forward");
                    if (currentDirection != "forward") {
                        sendData("{\"cmd\":\"forward\",\"arg\":\"\",\"id\":\""+id+"\"}\n");
                        currentDirection = "forward";
                    }
                } else {
                    Log.d(TAG, "backward");
                    if (currentDirection != "backward") {
                        sendData("{\"cmd\":\"backward\",\"arg\":\"\",\"id\":\""+id+"\"}\n");
                        currentDirection = "backward";
                    }
                }
            } else {
                /* stop */
                Log.d(TAG, "stop");
                if (currentDirection != "stop") {
                    sendData("{\"cmd\":\"stop\",\"arg\":\"\",\"id\":\""+id+"\"}\n");
                    currentDirection = "stop";
                }
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accel(event);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mag(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void selectBluetoothDevice() {
        Log.d(TAG, "showing bluetooth devices");
        /* clear previously found devices */
        mBluetoothDevices.clear();
        /* add bonded devices */
        mBluetoothDevices.addAll(mBluetoothAdapter.getBondedDevices());
        int index = 0;
        String[] bluetoothDeviceNames = new String[mBluetoothDevices.size()];
        for (BluetoothDevice device : mBluetoothDevices) {
            bluetoothDeviceNames[index++] = device.getName();
        }
        /* show bluetooth devices for selection */
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
        alertDialog.setCancelable(true);
        alertDialog.setTitle("Please select your sumorobot");
        alertDialog.setItems(bluetoothDeviceNames, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int selectedIndex) {
                sumorobotAddress = mBluetoothDevices.get(selectedIndex).getAddress();
                /* make sure it's disconnected */
                disconnect();
                /* connect to the selected sumorobot */
                connect(sumorobotAddress);
                dialog.dismiss();
            }
        });
        alertDialog.create();
        alertDialog.show();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("sendCommands")) {
            final String commands = args.getString(0);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    /* send the commands to the sumorobot */
                    if (mConnectionState == STATE_CONNECTED) {
                        sendData(commands);
                    }
                }
            }).start();
            callbackContext.success();
            return true;
        } else if (action.equals("selectSumorobot")) {
            /* show dialog to select a bluetooth device */
            selectBluetoothDevice();
            callbackContext.success();
            return true;
        } else if (action.equals("triggerOrientationMovement")) {
            isOrientationMovementActivated = !isOrientationMovementActivated;
            callbackContext.success();
            return true;
        }
        callbackContext.error("unknown action: " + action);
        return false;
    }
}