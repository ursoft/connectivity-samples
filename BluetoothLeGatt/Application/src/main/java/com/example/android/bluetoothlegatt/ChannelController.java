package com.example.android.bluetoothlegatt;

import android.os.RemoteException;
import android.util.Log;
import com.dsi.ant.channel.AntChannel;
import com.dsi.ant.channel.AntCommandFailedException;
import com.dsi.ant.channel.IAntChannelEventHandler;
import com.dsi.ant.message.ChannelId;
import com.dsi.ant.message.ChannelType;
import com.dsi.ant.message.fromant.AcknowledgedDataMessage;
import com.dsi.ant.message.fromant.BroadcastDataMessage;
import com.dsi.ant.message.fromant.ChannelEventMessage;
import com.dsi.ant.message.fromant.MessageFromAntType;
import com.dsi.ant.message.ipc.AntMessageParcel;
import java.util.Random;
import com.dsi.ant.message.EventCode;

public class ChannelController {
    private static final int CHANNEL_PROOF_DEVICE_TYPE = 11;
    private static final int CHANNEL_PROOF_FREQUENCY = 57;
    private static final int CHANNEL_PROOF_PERIOD = 8182;
    private static final int CHANNEL_PROOF_TRANSMISSION_TYPE = 5;
    private static final String TAG = ("SCHW2A" + ChannelController.class.getSimpleName());
    private static Random randGen = new Random();
    private final byte CALIBRATION_NUMBER = 1;
    private final byte GET_SET_PARAMETER_NUMBER = 2;
    private Boolean eventCountBit = false;
    private AntChannel mAntChannel;
    private ChannelBroadcastListener mChannelBroadcastListener;
    private ChannelEventCallback mChannelEventCallback = new ChannelEventCallback();
    private ChannelInfo mChannelInfo;
    private boolean mIsOpen;

    public static abstract class ChannelBroadcastListener {
        public abstract void onBroadcastChanged(ChannelInfo channelInfo);
    }

    public ChannelController(AntChannel antChannel, boolean z, int i, ChannelBroadcastListener channelBroadcastListener) {
        this.mAntChannel = antChannel;
        this.mChannelInfo = new ChannelInfo(i, z, 0);
        this.mChannelBroadcastListener = channelBroadcastListener;
        openChannel();
    }

    /* access modifiers changed from: package-private */
    public boolean openChannel() {
        if (this.mAntChannel == null) {
            Log.w(TAG, "No channel available");
        } else if (this.mIsOpen) {
            Log.w(TAG, "Channel was already open");
        } else {
            ChannelType channelType = this.mChannelInfo.isMaster ? ChannelType.BIDIRECTIONAL_MASTER : ChannelType.BIDIRECTIONAL_SLAVE;
            ChannelId channelId = new ChannelId(this.mChannelInfo.deviceNumber, 11, 5);
            try {
                this.mAntChannel.setChannelEventHandler(this.mChannelEventCallback);
                this.mAntChannel.assign(channelType);
                this.mAntChannel.setChannelId(channelId);
                this.mAntChannel.setPeriod(CHANNEL_PROOF_PERIOD);
                this.mAntChannel.setRfFrequency(57);
                this.mAntChannel.setBroadcastData(BluetoothLeService.serv.page10);
                this.mAntChannel.open();
                this.mIsOpen = true;
                String str = TAG;
                Log.d(str, "Opened channel with device number: " + this.mChannelInfo.deviceNumber);
            } catch (RemoteException e) {
                channelError(e);
            } catch (AntCommandFailedException e2) {
                channelError("Open failed", e2);
            }
        }
        return this.mIsOpen;
    }

    public class ChannelEventCallback implements IAntChannelEventHandler {
        public ChannelEventCallback() {
        }

        private void updateData(byte[] bArr) {
            ChannelController.this.mChannelInfo.broadcastData = bArr;
            ChannelController.this.mChannelBroadcastListener.onBroadcastChanged(ChannelController.this.mChannelInfo);
            if (bArr[0] == 1 && bArr[1] == -86) {
                BluetoothLeService.serv.pendingCalibrationRequest = true;
            }
            if (bArr[0] == 1 && bArr[1] == -85) {
                BluetoothLeService.serv.pendingAutoZeroRequest = true;
            }
            if (bArr[0] == 70 && bArr[6] == 80) {
                BluetoothLeService.serv.page10[0] = 80;
                BluetoothLeService.serv.page10[1] = -1;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = 1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = 0;
                BluetoothLeService.serv.page10[6] = 1;
                BluetoothLeService.serv.page10[7] = 0;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused) {
                }
                if ((bArr[5] & Byte.MIN_VALUE) == 1) {
                    BluetoothLeService.serv.pendingPowerManufacturerRequest = 0;
                    BluetoothLeService.serv.pendingManufacturerPowerPageAcknowledged = true;
                    return;
                }
                BluetoothLeService.serv.pendingPowerManufacturerRequest = bArr[5] & Byte.MAX_VALUE;
            }
        }

        @Override // com.dsi.ant.channel.IAntChannelEventHandler
        public void onChannelDeath() {
            ChannelController.this.displayChannelError("Channel Death");
        }

        @Override // com.dsi.ant.channel.IAntChannelEventHandler
        public void onReceiveMessage(MessageFromAntType messageFromAntType, AntMessageParcel antMessageParcel) {
            int power = 0;
            if(System.currentTimeMillis() - BluetoothLeService.serv.mLastRxTime < 1000) {
                power = (int)BluetoothLeService.serv.mLastPower;
            }
            ChannelController channelController = ChannelController.this;
            channelController.eventCountBit = Boolean.valueOf(!channelController.eventCountBit.booleanValue());
            if (ChannelController.this.eventCountBit.booleanValue()) {
                BluetoothLeService.serv.acc_power += power;
                BluetoothLeService.serv.acc_power %= 65536;
                BluetoothLeService.serv.cnt++;
                BluetoothLeService.serv.cnt %= 256;
            }
            if (messageFromAntType == MessageFromAntType.BROADCAST_DATA) {
                updateData(new BroadcastDataMessage(antMessageParcel).getPayload());
            } else if (messageFromAntType == MessageFromAntType.ACKNOWLEDGED_DATA) {
                updateData(new AcknowledgedDataMessage(antMessageParcel).getPayload());
            } else if (messageFromAntType == MessageFromAntType.CHANNEL_EVENT) {
                EventCode i2 = (new ChannelEventMessage(antMessageParcel)).getEventCode();
                if (i2 == EventCode.RX_SEARCH_TIMEOUT) {
                    ChannelController.this.mChannelBroadcastListener.onBroadcastChanged(ChannelController.this.mChannelInfo);
                    byte[] bArr = ChannelController.this.mChannelInfo.broadcastData;
                    bArr[0] = (byte) (bArr[0] + 1);
                } else if (i2 == EventCode.RX_FAIL) {
                    ChannelController.this.displayChannelError("No Device Found");
                }
            }
            if (BluetoothLeService.serv.pendingCalibrationRequest.booleanValue()) {
                BluetoothLeService.serv.pendingCalibrationRequest = false;
                BluetoothLeService.serv.page10[0] = 1;
                BluetoothLeService.serv.page10[1] = -84;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = -1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = -1;
                BluetoothLeService.serv.page10[6] = 0;
                BluetoothLeService.serv.page10[7] = 0;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused) {
                }
            } else if (BluetoothLeService.serv.pendingAutoZeroRequest.booleanValue()) {
                BluetoothLeService.serv.pendingAutoZeroRequest = false;
                BluetoothLeService.serv.page10[0] = 1;
                BluetoothLeService.serv.page10[1] = -81;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = -1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = -1;
                BluetoothLeService.serv.page10[6] = 0;
                BluetoothLeService.serv.page10[7] = 0;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused2) {
                }
            } else if (BluetoothLeService.serv.pendingManufacturerPowerPageAcknowledged.booleanValue()) {
                BluetoothLeService.serv.pendingManufacturerPowerPageAcknowledged = false;
                BluetoothLeService.serv.page10[0] = 80;
                BluetoothLeService.serv.page10[1] = -1;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = 1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = 0;
                BluetoothLeService.serv.page10[6] = 1;
                BluetoothLeService.serv.page10[7] = 0;
                try {
                    BluetoothLeService.serv.mANTChannel.startSendAcknowledgedData(BluetoothLeService.serv.page10);
                } catch (Exception unused3) {
                    BluetoothLeService.serv.pendingManufacturerPowerPageAcknowledged = true;
                }
            } else if (BluetoothLeService.serv.cnt % 30 == 0) {
                BluetoothLeService.serv.page10[0] = 81;
                BluetoothLeService.serv.page10[1] = -1;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = 2;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = -1;
                BluetoothLeService.serv.page10[6] = -1;
                BluetoothLeService.serv.page10[7] = -1;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused4) {
                }
            } else if (BluetoothLeService.serv.cnt % 25 == 0 || BluetoothLeService.serv.pendingPowerManufacturerRequest > 0) {
                BluetoothLeService.serv.pendingPowerManufacturerRequest--;
                BluetoothLeService.serv.page10[0] = 80;
                BluetoothLeService.serv.page10[1] = -1;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = 1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = 0;
                BluetoothLeService.serv.page10[6] = 1;
                BluetoothLeService.serv.page10[7] = 0;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused5) {
                }
            } else if (BluetoothLeService.serv.cnt % 27 == 0) {
                BluetoothLeService.serv.page10[0] = 1;
                BluetoothLeService.serv.page10[1] = 18;
                BluetoothLeService.serv.page10[2] = 0;
                BluetoothLeService.serv.page10[3] = -1;
                BluetoothLeService.serv.page10[4] = -1;
                BluetoothLeService.serv.page10[5] = -1;
                BluetoothLeService.serv.page10[6] = -1;
                BluetoothLeService.serv.page10[7] = -1;
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused6) {
                }
            } else {
                BluetoothLeService.serv.page10[0] = 16;
                BluetoothLeService.serv.page10[1] = (byte) BluetoothLeService.serv.cnt;
                BluetoothLeService.serv.page10[2] = -1;
                BluetoothLeService.serv.page10[3] = (byte) BluetoothLeService.serv.currentCadence();
                BluetoothLeService.serv.page10[4] = (byte) BluetoothLeService.serv.acc_power;
                BluetoothLeService.serv.page10[5] = (byte) (BluetoothLeService.serv.acc_power >> 8);
                BluetoothLeService.serv.page10[6] = (byte) power;
                BluetoothLeService.serv.page10[7] = (byte) (power >> 8);
                try {
                    BluetoothLeService.serv.mANTChannel.setBroadcastData(BluetoothLeService.serv.page10);
                } catch (Exception unused7) {
                }
            }
        }
    }

    public ChannelInfo getCurrentInfo() {
        return this.mChannelInfo;
    }

    /* access modifiers changed from: package-private */
    public void displayChannelError(String str) {
        this.mChannelInfo.die(str);
        this.mChannelBroadcastListener.onBroadcastChanged(this.mChannelInfo);
    }

    /* access modifiers changed from: package-private */
    public void channelError(RemoteException remoteException) {
        Log.e(TAG, "Remote service communication failed.");
        displayChannelError("Remote service communication failed.");
    }

    /* access modifiers changed from: package-private */
    public void channelError(String str, AntCommandFailedException antCommandFailedException) {
        StringBuilder sb;
        if (antCommandFailedException.getResponseMessage() != null) {
            String str2 = "0x" + Integer.toHexString(antCommandFailedException.getResponseMessage().getInitiatingMessageId());
            sb = new StringBuilder(str);
            sb.append(". Command ");
            sb.append(str2);
            sb.append(" failed with code ");
            sb.append("0x" + Integer.toHexString(antCommandFailedException.getResponseMessage().getRawResponseCode()));
        } else {
            String str3 = "0x" + Integer.toHexString(antCommandFailedException.getAttemptedMessageType().getMessageId());
            String antCommandFailureReason = antCommandFailedException.getFailureReason().toString();
            sb = new StringBuilder(str);
            sb.append(". Command ");
            sb.append(str3);
            sb.append(" failed with reason ");
            sb.append(antCommandFailureReason);
        }
        Log.e(TAG, sb.toString());
        this.mAntChannel.release();
        displayChannelError("ANT Command Failed");
    }

    public void close() {
        AntChannel antChannel = this.mAntChannel;
        if (antChannel != null) {
            this.mIsOpen = false;
            antChannel.release();
            this.mAntChannel = null;
        }
        displayChannelError("Channel Closed");
    }
}