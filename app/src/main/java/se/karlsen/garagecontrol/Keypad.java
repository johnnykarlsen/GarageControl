package se.karlsen.garagecontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.api.GoogleApiClient;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;


public class Keypad extends AppCompatActivity {

    private class BluetoothReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                //mBluetoothAdapter = bluetoothManager.getAdapter();
                mBluetoothAdapter.enable();
                Log.v(TAG,"Trying to enable again");
            } else if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON)
                Log.v(TAG,"Enabled, trying to connect again");
                connectToGarage();
        }
    }

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothScanner;
    private boolean mScanning = false;
    private Handler mHandler;
    private BluetoothDevice mGarage = null;
    private BluetoothGatt mGarageGatt = null;
    private BluetoothGattService mPortService = null;
    private boolean mGarageConnected = false;
    private AlertDialog mDialog = null;
    private ImageView mFlashConnect = null;
    private Animation mBlinkAnimation = null;
    private KeyAdapter mKeyAdapter = null;
    private byte[] mPassWordBytes = null;
    private long mStartToConnectTime = 0;
    private BluetoothReceiver mBluetoothReceiver = null;

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_ENABLE_BT_AGAIN = 2;
    private static final int FAIL_TO_CONNECT = 3;
    private static final int SUCCESS_TO_CONNECT = 4;
    private static final int SUCCESS_TO_DISCONNECT = 5;
    private static final int INCORRECT_PASSWORD = 6;
    private static final int NONCE_LENGTH = 20;
    private static final int PASSWORD_LENGTH = 6;
    private static final long SCAN_PERIOD = 10000;
    private static final String TAG = "garageControl";
    private static final String GARAGE = "Karlsen_Garage";

    private static final long CHAR_UUID_MSB = 0xc2f0a001d15e8fa4L;
    private static final long CHAR_UUID_LSB = 0xaa4d0fe7cf8b0d22L;
    private static final long CHAR_VALID_PASSWORD_UUID_MSB = 0xc2f0a002d15e8fa4L;
    private static final long CHAR_VALID_PASSWORD_UUID_LSB = 0xaa4d0fe7cf8b0d22L;
    private static final long SERVICE_UUID_MSB = 0xc2f0a000d15e8fa4L;
    private static final long SERVICE_UUID_LSB = 0xaa4d0fe7cf8b0d22L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keypad);

        this.setFinishOnTouchOutside(false);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mFlashConnect = (ImageView) findViewById(R.id.toolbar_flash);
        mFlashConnect.setVisibility(View.INVISIBLE);

        //Only makes sense in normal orientation
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        GridView keyboardView = (GridView) findViewById(R.id.keyboardview);
        mKeyAdapter = new KeyAdapter(this);
        keyboardView.setAdapter(mKeyAdapter);

        keyboardView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                TextView passwordView = (TextView) findViewById(R.id.password);
                Log.v(TAG, KeyAdapter.KEYS[position]);
                //If delete key
                if (KeyAdapter.KEYS[position] == "d") {
                    passwordView.setText(passwordView.getText().subSequence(0, Math.max(passwordView.getText().length()-1,0)));
                } else if (KeyAdapter.KEYS[position] == "v") {
                    if (mGarageConnected && passwordView.getText().length() == PASSWORD_LENGTH) {
                        mPassWordBytes = convertPassword(passwordView.getText());
                        //Try to open the garage port
                        boolean res = mGarageGatt.discoverServices();
                        Log.v(TAG, Boolean.toString(res));
                    }
                } else
                    passwordView.setText(passwordView.getText() + KeyAdapter.KEYS[position]);
                if (mGarageConnected && passwordView.getText().length() == PASSWORD_LENGTH)
                    mKeyAdapter.alterForwardKey(KeyAdapter.BLACK);
                else
                    mKeyAdapter.alterForwardKey(KeyAdapter.GREY);
            }
        });

        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        mBluetoothReceiver = new BluetoothReceiver();
        IntentFilter filter = new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED");
        registerReceiver(mBluetoothReceiver, filter);

        //Create animation for the connection indication
        mBlinkAnimation = new AlphaAnimation(1,0);
        mBlinkAnimation.setDuration(500);
        mBlinkAnimation.setInterpolator(new LinearInterpolator());
        mBlinkAnimation.setRepeatCount(Animation.INFINITE);
        mBlinkAnimation.setRepeatMode(Animation.REVERSE);

        mHandler = new Handler(Looper.getMainLooper()) {

            @Override
            public void handleMessage(Message in) {

                switch (in.what) {

                    case FAIL_TO_CONNECT:
                        //Try to handle some race/malfunction in the stack
                        //If the time between start of connect try and callback is to short do it over again
                        if (System.currentTimeMillis() - mStartToConnectTime < 1000) {
                            Log.v(TAG,"Short time between connect and fail, reset Bluetooth");
                            mBluetoothAdapter.disable();
                            Toast.makeText(getApplicationContext(),R.string.bluetooth_reset, Toast.LENGTH_SHORT).show();
                            Log.v(TAG,"VVVVVVVVVVVVV");
                            break;
                            //Log.v(TAG,"YYYYYYYYYYY");
                        }
                        Log.v(TAG,"XXXXXXXXXXXXXX");
                        mFlashConnect.clearAnimation();
                        mFlashConnect.setVisibility(View.INVISIBLE);
                        mKeyAdapter.alterForwardKey(KeyAdapter.GREY);
                        mGarageConnected = false;
                        AlertDialog.Builder builder = new AlertDialog.Builder(Keypad.this);
                        builder.setMessage(R.string.not_able_to_connect);
                        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                //Try to connect again
                                connectToGarage();
                            }
                        });
                        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                //Cancel dialog
                            }
                        });
                        mDialog = builder.create();
                        mDialog.setCanceledOnTouchOutside(false);
                        mDialog.show();
                        break;
                    case SUCCESS_TO_CONNECT:
                        mFlashConnect.clearAnimation();
                        mGarageConnected = true;
                        //Check if password already entered PA digits, in that case..
                        TextView passwordView = (TextView) findViewById(R.id.password);
                        if (passwordView.getText().length() == PASSWORD_LENGTH)
                            mKeyAdapter.alterForwardKey(KeyAdapter.BLACK);
                        break;
                    case SUCCESS_TO_DISCONNECT:
                        mKeyAdapter.alterForwardKey(KeyAdapter.GREY);
                        break;
                    case INCORRECT_PASSWORD:
                        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                        vibrator.vibrate(500);
                        Toast.makeText(getApplicationContext(),R.string.incorrect_password, Toast.LENGTH_SHORT).show();
                }
            }
        };

        Log.v(TAG, "did something");
        if (mBluetoothAdapter != null)
            Log.v(TAG, "not null adapter");
    }

    @Override
    public void onResume() {
        super.onResume();  // Always call the superclass method first
        Log.v(TAG, "onResume");

        //Check if Bluetooth is enabled
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            //Fetch Garage device from bonded set
            for (BluetoothDevice dev : mBluetoothAdapter.getBondedDevices()) {
                if ((dev.getName() != null) && (dev.getName().equals(GARAGE))) {
                    mGarage = dev;
                    Log.v(TAG, "assigned mGarage");
                    connectToGarage();
                    return;
                }
            }
            //No garage stored, need to scan for it
            Log.v(TAG, "No garage stored");
            AlertDialog.Builder builder = new AlertDialog.Builder(Keypad.this);
            builder.setMessage(R.string.no_garage_stored);
            builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Intent scanForGarageIntent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    startActivityForResult(scanForGarageIntent, 0);
                }
            });
            mDialog = builder.create();
            mDialog.show();
        }



    }

    @Override
    public void onPause() {
        super.onPause();  // Always call the superclass method first
        Log.v(TAG, "onPause");
        //Close any open dialogs
        if (mDialog != null)
            mDialog.dismiss();
        if (mGarageGatt != null) {
            mGarageGatt.disconnect();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.v(TAG, "onStop");
    }

    @Override
    public void onRestart() {
        super.onRestart();  // Always call the superclass method first
        Log.v(TAG, "onRestart");
    }

    @Override
    public void onStart() {
        super.onStart();

  /*      if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            //Start scanning if there is no device
            if (mGarage == null) {
                scanGarageBLE(true);
            }
        }*/
        //connectToGarage();
        Log.v(TAG, "onStart");
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy");
        super.onDestroy();  // Always call the superclass method first

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        Log.v(TAG, "got a result");


        if ((requestCode == REQUEST_ENABLE_BT) && (resultCode == Activity.RESULT_CANCELED)) {
            this.finishAffinity();
        }
/*          //The user cancelled, inform about need
            if (resultCode == Activity.RESULT_CANCELED) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(R.string.ble_dialog);
                builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        //Kick of BT request again
                        if (!mBluetoothAdapter.isEnabled()) {
                            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT_AGAIN);
                        }
                    }
                });
                AlertDialog dialog = builder.create();
                dialog.show();
                Log.v(TAG, "result cancelled");
            }
            //We have kicked of the BT request again
        } else if (requestCode == REQUEST_ENABLE_BT_AGAIN) {
            //The stupid user cancelled again, kill application
            if (resultCode == Activity.RESULT_CANCELED) {
                this.finishAffinity();
            }


        }*/
    }//onActivityResult
/*
    private void scanGarageBLE(final boolean enable) {

        final ScanCallback mScanCallback = new ScanCallback() {

            public void onScanResult(int callbackType, ScanResult result) {

                Log.v(TAG, "some scan result coming");
                Log.v(TAG, Integer.toString(callbackType));
                String tmp = result.toString();
                Log.v(TAG, tmp);
                if ((result.getDevice().getName() != null) && result.getDevice().getName().equals(GARAGE)) {
                    mGarage = result.getDevice();
                    mScanning = false;
                    mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
                    //mScanning = false;
                    mBluetoothScanner.stopScan(this);
                    Log.v(TAG, "stopping to scan");
                    mGarage.createBond();//connectToGarage();
                }
            }

            @Override
            public void onScanFailed(int errorCode) {

                Log.v(TAG, "failed scan");
            }

            public void onBatchScanResults(List<ScanResult> results) {

                Log.v(TAG, "batch scan result");
            }

        };

        if (enable) {
            mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
            mHandler = new Handler();
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    //Scan time ended without finding device
                    if (mScanning) {
                        mScanning = false;
                        mBluetoothScanner.stopScan(mScanCallback);
                        Log.v(TAG, "stopping to scan");
                        AlertDialog.Builder builder = new AlertDialog.Builder(Keypad.this);
                        builder.setMessage(R.string.non_found_scan_dialog);
                        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                //Scan again
                                scanGarageBLE(true);
                            }
                        });
                        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                //Cancel application
                                Keypad.this.finishAffinity();
                            }
                        });
                        AlertDialog dialog = builder.create();
                        dialog.show();
                    }

                }
            }, SCAN_PERIOD);
            mScanning = true;
            mBluetoothScanner.startScan(mScanCallback);
            Log.v(TAG, "starting to scan");
        } else {
            mScanning = false;
            mBluetoothScanner.stopScan(mScanCallback);
        }
    }
*/


    private void connectToGarage() {

        //Can I define this one here since it will survive anyway via the callback reference?!
        final BluetoothGattCallback mBGCallback = new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                String st = "OnConnectionStatuschange: " + Integer.toString(status);
                Log.v(TAG,st);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        //A successful connection attempt
                        Message successToConnectMessage = mHandler.obtainMessage(SUCCESS_TO_CONNECT);
                        successToConnectMessage.sendToTarget();
                    } else {
                        //A successful disconnection attempt
                        Message successToDisconnectMessage = mHandler.obtainMessage(SUCCESS_TO_DISCONNECT);
                        successToDisconnectMessage.sendToTarget();
                        mGarageGatt.close();
                        Log.v(TAG,"A successful disconnect");
                    }
                } else {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        //A failed disconnect attempt??, can it happen?
                        Log.v(TAG,"A failed disconnect attempt, shouldn't really happen");
                    } else {
                        //A failed connection attempt or lost connection
                        Message failToConnectMessage = mHandler.obtainMessage(FAIL_TO_CONNECT);
                        failToConnectMessage.sendToTarget();
                        mGarageGatt.close();
                        Log.v(TAG,"Failed connection attempt or lost connection");
                    }
                }
            }

            @Override
            // Result of a characteristic read operation, in this case a challenge retrieved
            public void onCharacteristicRead(BluetoothGatt gatt,
                                             BluetoothGattCharacteristic characteristic,
                                             int status) {

                Log.v(TAG, Integer.toString(status));
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (characteristic.getUuid().equals(new UUID(CHAR_VALID_PASSWORD_UUID_MSB, CHAR_VALID_PASSWORD_UUID_LSB))) {
                        Log.v(TAG, "Confirm value: ");
                        Log.v(TAG, Byte.toString(characteristic.getValue()[0]));
                        if (characteristic.getValue()[0] == 0) {
                            Message incorrectPasswordMessage = mHandler.obtainMessage(INCORRECT_PASSWORD);
                            incorrectPasswordMessage.sendToTarget();
                        }
                    } else {
                        byte[] result = calculateHash(characteristic.getValue());
                        Log.v(TAG, (new String(result)));
                        Log.v(TAG, "Length of hash:" + Integer.toString(result.length));
                        if (result != null) {
                            characteristic.setValue(result);
                            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                            boolean test = mGarageGatt.writeCharacteristic(characteristic);
                            Log.v(TAG, Boolean.toString(test));
                        }
                    }
                }
            }

            @Override
            // Result of a characteristic write operation, written the password challenge response
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    byte[] result;
                    result = characteristic.getValue();
                    Log.v(TAG, (new String(result)));
                    if (mPortService !=null) {
                        UUID charUuid = new UUID(CHAR_VALID_PASSWORD_UUID_MSB, CHAR_VALID_PASSWORD_UUID_LSB);
                        BluetoothGattCharacteristic portPasswordConfirmCharacteristic = mPortService.getCharacteristic(charUuid);
                        if (portPasswordConfirmCharacteristic != null) {
                            //This is a request for password validity
                            boolean res = mGarageGatt.readCharacteristic(portPasswordConfirmCharacteristic);
                        }
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    UUID serviceUuid = new UUID(SERVICE_UUID_MSB,SERVICE_UUID_LSB);
                    mPortService = gatt.getService(serviceUuid);
                    if (mPortService !=null) {
                        UUID charUuid = new UUID(CHAR_UUID_MSB, CHAR_UUID_LSB);
                        BluetoothGattCharacteristic portCharacteristic = mPortService.getCharacteristic(charUuid);
                        if (portCharacteristic != null) {
                            //This is a request for the challenge
                            boolean res = mGarageGatt.readCharacteristic(portCharacteristic);
                        }
                    }
                }
            }
        };

        Log.v(TAG, "Trying to connect");
        mFlashConnect.setVisibility(View.VISIBLE);
        mFlashConnect.startAnimation(mBlinkAnimation);
        mGarageGatt = mGarage.connectGatt(this, false, mBGCallback);                //Starting connect attempt
        mStartToConnectTime = System.currentTimeMillis();                           //Store start time
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        Log.v(TAG, "onCreateOptionsMenu");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        Log.v(TAG, "onOptionsItemSelected");

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private byte[] convertPassword(CharSequence password) {

        byte[] passBytes = new byte[PASSWORD_LENGTH];

        Log.v(TAG, Integer.toString(password.length()));
        for (int i = 0; i < password.length(); i++) {
            passBytes[i] = (byte) password.charAt(i);
        }
        return passBytes;
    }

    private byte[] calculateHash(byte[] nonce) {

        byte[] noncePassword = new byte[NONCE_LENGTH+PASSWORD_LENGTH];

        for (int i = 0; i < NONCE_LENGTH+PASSWORD_LENGTH; i++) {
            if (i < NONCE_LENGTH) {
                noncePassword[i] = nonce[i];
            } else {
                noncePassword[i] = mPassWordBytes[i-NONCE_LENGTH];
                Log.v(TAG, Byte.toString(noncePassword[i]));
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(noncePassword);
            byte[] digest = md.digest();
            return digest;
        } catch (NoSuchAlgorithmException nsae) {
            return null;
        }
    }
}
