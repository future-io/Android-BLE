package cn.com.heaton.blelibrary.ble.request;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import cn.com.heaton.blelibrary.ble.Ble;
import cn.com.heaton.blelibrary.ble.model.ScanRecord;
import cn.com.heaton.blelibrary.ble.factory.BleFactory;
import cn.com.heaton.blelibrary.ble.BleHandler;
import cn.com.heaton.blelibrary.ble.BleStates;
import cn.com.heaton.blelibrary.ble.L;
import cn.com.heaton.blelibrary.ble.annotation.Implement;
import cn.com.heaton.blelibrary.ble.callback.BleScanCallback;
import cn.com.heaton.blelibrary.ble.BleDevice;

/**
 *
 * Created by LiuLei on 2017/10/21.
 */
@Implement(ScanRequest.class)
public class ScanRequest<T extends BleDevice> implements IMessage {

    private boolean mScanning;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mScanner;
    private ScanSettings mScannerSetting;
    private BleScanCallback<T> mScanCallback;
    private BLEScanCallback mScannerCallback;
    private List<ScanFilter> mFilters;
    //    private AtomicBoolean isContains = new AtomicBoolean(false);
    private ArrayList<T> mScanDevices = new ArrayList<>();
    private Ble<T> mBle;

    protected ScanRequest() {
        mBle = Ble.getInstance();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        BleHandler.getHandler().setHandlerCallback(this);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            //mScanner will be null if Bluetooth has been closed
            mScanner = mBluetoothAdapter.getBluetoothLeScanner();
            mScannerSetting = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            mScannerCallback  = new BLEScanCallback();
            mFilters = new ArrayList<>();
        }
    }

    public void startScan(BleScanCallback<T> callback, int scanPeriod) {
        if(mScanning)return;
        if(callback != null){
            mScanCallback = callback;
        }
        mScanning = true;
        // Stops scanning after a pre-defined scan period.
        BleHandler.getHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if(mScanning){
                    stopScan();
                }
            }
        }, scanPeriod);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            //the status of bluetooth will be checked in the original codes of startScan().
            //once bluetooth is closed,it will throw an exception, so to avoid this, it's
            //necessary to check the status of bluetooth before calling startScan()
            if (mBluetoothAdapter.isEnabled()) {
                //mScanner may be null when it was initialized without opening bluetooth, so recheck it
                if (mScanner == null) {
                    mScanner = mBluetoothAdapter.getBluetoothLeScanner();
                }
//                byte[] manufacture = {0x00, 0x2A};
//                mFilters.add(new ScanFilter.Builder()
//                        .setServiceUuid(ParcelUuid.fromString("0000ae00-0000-1000-8000-00805f9b34fb"))
//                        .setManufacturerData(0x5254, manufacture)
//                        .build());
                mScanner.startScan(mFilters, mScannerSetting, mScannerCallback);
            }
        }
        if(callback != null){
            mScanCallback.onStart();
        }
    }

    public void stopScan() {
        if (!mScanning) return;
        mScanning = false;
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP){
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }else {
            if (mBluetoothAdapter.isEnabled()) {
                if (mScanner == null) {
                    mScanner = mBluetoothAdapter.getBluetoothLeScanner();
                }
                mScanner.stopScan(mScannerCallback);
            }
        }
        mScanDevices.clear();
        if(mScanCallback != null){
            mScanCallback.onStop();
        }
    }

    public boolean isScanning() {
        return mScanning;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private class BLEScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            byte[] scanRecord = result.getScanRecord().getBytes();
            if (device == null) return;
            T bleDevice = getDevice(device.getAddress());
            if (bleDevice == null) {
                bleDevice = (T) BleFactory.create(BleDevice.class, device);
                if(mScanCallback != null){
                    mScanCallback.onLeScan(bleDevice, result.getRssi(), scanRecord);
                }
                mScanDevices.add(bleDevice);
            }
            ScanRecord parseRecord = ScanRecord.parseFromBytes(scanRecord);
            if (parseRecord != null && mScanCallback != null){
                mScanCallback.onParsedData(bleDevice, parseRecord);
            }
            //自动重连
            autoConnect(device);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult sr : results) {
                L.i("ScanResult - Results", sr.toString());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            L.e("Scan Failed", "Error Code: " + errorCode);
        }
    }

    private void autoConnect(BluetoothDevice device) {
        synchronized (mBle.getLocker()) {
            for (T autoDevice : mBle.getAutoDevices()) {
                if (device.getAddress().equals(autoDevice.getBleAddress())) {
                    //Note non-active disconnect device in theory need to re-connect automatically (provided the connection is set to automatically connect property is true)
                    if (!autoDevice.isConnected() && !autoDevice.isConnectting() && autoDevice.isAutoConnect()) {
                        L.e("onScanResult", "onLeScan: " + "正在重连设备...");
                        mBle.reconnect(autoDevice);
                    }
                }
            }
        }
    }


    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, final byte[] scanRecord) {
            if (device == null) return;
            T bleDevice = getDevice(device.getAddress());
            if (bleDevice == null) {
                bleDevice = (T) BleFactory.create(BleDevice.class, device);
                if(mScanCallback != null){
                    mScanCallback.onLeScan(bleDevice, rssi, scanRecord);
                }
                mScanDevices.add(bleDevice);
            }
            //自动重连
            autoConnect(device);
        }
    };

    //获取已扫描到的设备（重复设备）
    private T getDevice(String address) {
        for (T device : mScanDevices) {
            if (device.getBleAddress().equals(address)) {
                return device;
            }
        }
        return null;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what){
            case BleStates.BleStatus.BlutoothStatusOff:
                if(mScanning){
                    stopScan();
                }
                break;
        }
    }
}
