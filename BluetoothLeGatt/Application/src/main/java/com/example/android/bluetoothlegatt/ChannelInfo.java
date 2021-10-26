package com.example.android.bluetoothlegatt;

public class ChannelInfo {
    public byte[] broadcastData;
    public final int deviceNumber;
    public boolean error = false;
    public final boolean isMaster;
    private String mErrorMessage = null;

    public ChannelInfo(int i, boolean z, int i2) {
        byte[] bArr = new byte[8];
        this.broadcastData = bArr;
        this.deviceNumber = i;
        this.isMaster = z;
        bArr[0] = (byte) i2;
    }

    public void die(String str) {
        this.error = true;
        this.mErrorMessage = str;
    }

    public String getErrorString() {
        return this.mErrorMessage;
    }
}