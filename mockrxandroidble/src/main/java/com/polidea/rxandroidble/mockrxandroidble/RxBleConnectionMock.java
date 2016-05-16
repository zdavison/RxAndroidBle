package com.polidea.rxandroidble.mockrxandroidble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;

import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.exceptions.BleConflictingNotificationAlreadySetException;
import com.polidea.rxandroidble.internal.util.ObservableUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import rx.Observable;

import static android.bluetooth.BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;

class RxBleConnectionMock implements RxBleConnection {

    static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private HashMap<UUID, Observable<Observable<byte[]>>> notificationObservableMap = new HashMap<>();
    private HashMap<UUID, Observable<Observable<byte[]>>> indicationObservableMap = new HashMap<>();
    private RxBleDeviceServices rxBleDeviceServices;
    private int rssi;
    private Map<UUID, Observable<byte[]>> characteristicNotificationSources;
    private Map<UUID, Observable<byte[]>> characteristicIndicationSources;


    public RxBleConnectionMock(RxBleDeviceServices rxBleDeviceServices,
                               int rssi,
                               Map<UUID, Observable<byte[]>> characteristicNotificationSources,
                               Map<UUID, Observable<byte[]>> characteristicIndicationSources) {
        this.rxBleDeviceServices = rxBleDeviceServices;
        this.rssi = rssi;
        this.characteristicNotificationSources = characteristicNotificationSources;
        this.characteristicIndicationSources = characteristicIndicationSources;
    }

    @Override
    public Observable<RxBleDeviceServices> discoverServices() {
        return Observable.just(rxBleDeviceServices);
    }

    @Override
    public Observable<BluetoothGattCharacteristic> getCharacteristic(@NonNull UUID characteristicUuid) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getCharacteristic(characteristicUuid));
    }

    @Override
    public Observable<byte[]> readCharacteristic(@NonNull UUID characteristicUuid) {
        return getCharacteristic(characteristicUuid).map(BluetoothGattCharacteristic::getValue);
    }

    @Override
    public Observable<byte[]> readDescriptor(UUID serviceUuid, UUID characteristicUuid, UUID descriptorUuid) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid))
                .map(BluetoothGattDescriptor::getValue);
    }

    @Override
    public Observable<Integer> readRssi() {
        return Observable.just(rssi);
    }

    @Override
    public Observable<Observable<byte[]>> setupIndication(@NonNull UUID characteristicUuid) {

        if (notificationObservableMap.containsKey(characteristicUuid)) {
            return Observable.error(new BleConflictingNotificationAlreadySetException(characteristicUuid, false));
        }

        Observable<Observable<byte[]>> availableObservable = indicationObservableMap.get(characteristicUuid);

        if (availableObservable != null) {
            return availableObservable;
        }

        Observable<Observable<byte[]>> newObservable = createCharacteristicIndicationObservable(characteristicUuid)
                .doOnUnsubscribe(() -> dismissCharacteristicIndication(characteristicUuid))
                .map(indicationDescriptorData -> observeOnCharacteristicChangeIndicationCallbacks(characteristicUuid))
                .replay(1)
                .refCount();
        indicationObservableMap.put(characteristicUuid, newObservable);

        return newObservable;
    }

    @Override
    public Observable<Observable<byte[]>> setupNotification(@NonNull UUID characteristicUuid) {

        if (indicationObservableMap.containsKey(characteristicUuid)) {
            return Observable.error(new BleConflictingNotificationAlreadySetException(characteristicUuid, true));
        }

        Observable<Observable<byte[]>> availableObservable = notificationObservableMap.get(characteristicUuid);

        if (availableObservable != null) {
            return availableObservable;
        }

        Observable<Observable<byte[]>> newObservable = createCharacteristicNotificationObservable(characteristicUuid)
                .doOnUnsubscribe(() -> dismissCharacteristicNotification(characteristicUuid))
                .map(notificationDescriptorData -> observeOnCharacteristicChangeNotificationCallbacks(characteristicUuid))
                .replay(1)
                .refCount();
        notificationObservableMap.put(characteristicUuid, newObservable);

        return newObservable;
    }

    @Override
    public Observable<BluetoothGattCharacteristic> writeCharacteristic(@NonNull BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return getCharacteristic(bluetoothGattCharacteristic.getUuid())
                .map(characteristic -> characteristic.setValue(bluetoothGattCharacteristic.getValue()))
                .flatMap(ignored -> Observable.just(bluetoothGattCharacteristic));
    }

    @Override
    public Observable<byte[]> writeCharacteristic(@NonNull UUID characteristicUuid, @NonNull byte[] data) {
        return getCharacteristic(characteristicUuid)
                .map(characteristic -> characteristic.setValue(data))
                .flatMap(ignored -> Observable.just(data));
    }

    @Override
    public Observable<byte[]> writeDescriptor(UUID serviceUuid, UUID characteristicUuid, UUID descriptorUuid, byte[] data) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid))
                .map(bluetoothGattDescriptor -> bluetoothGattDescriptor.setValue(data)).flatMap(ignored -> Observable.just(data));
    }

    private Observable<Observable<byte[]>> createCharacteristicIndicationObservable(UUID characteristicUuid) {
        return getClientConfigurationDescriptor(characteristicUuid)
                .flatMap(bluetoothGattDescriptor -> setupCharacteristicIndication(bluetoothGattDescriptor, true))
                .flatMap(ObservableUtil::justOnNext)
                .flatMap(bluetoothGattDescriptorPair -> {
                    if (!characteristicIndicationSources.containsKey(characteristicUuid)) {
                        return Observable.error(new IllegalStateException("Lack of indication source for given characteristic"));
                    }
                    return Observable.just(characteristicIndicationSources.get(characteristicUuid));
                });
    }

    private Observable<Observable<byte[]>> createCharacteristicNotificationObservable(UUID characteristicUuid) {
        return getClientConfigurationDescriptor(characteristicUuid)
                .flatMap(bluetoothGattDescriptor -> setupCharacteristicNotification(bluetoothGattDescriptor, true))
                .flatMap(ObservableUtil::justOnNext)
                .flatMap(bluetoothGattDescriptorPair -> {
                    if (!characteristicNotificationSources.containsKey(characteristicUuid)) {
                        return Observable.error(new IllegalStateException("Lack of notification source for given characteristic"));
                    }
                    return Observable.just(characteristicNotificationSources.get(characteristicUuid));
                });
    }

    private void dismissCharacteristicIndication(UUID characteristicUuid) {
        indicationObservableMap.remove(characteristicUuid);
        getClientConfigurationDescriptor(characteristicUuid)
                .flatMap(descriptor -> setupCharacteristicIndication(descriptor, false))
                .subscribe(
                        ignored -> {
                        },
                        ignored -> {
                        });
    }

    private void dismissCharacteristicNotification(UUID characteristicUuid) {
        notificationObservableMap.remove(characteristicUuid);
        getClientConfigurationDescriptor(characteristicUuid)
                .flatMap(descriptor -> setupCharacteristicNotification(descriptor, false))
                .subscribe(
                        ignored -> {
                        },
                        ignored -> {
                        });
    }

    @NonNull
    private Observable<BluetoothGattDescriptor> getClientConfigurationDescriptor(UUID characteristicUuid) {
        return getCharacteristic(characteristicUuid)
                .map(bluetoothGattCharacteristic -> {
                    BluetoothGattDescriptor bluetoothGattDescriptor =
                            bluetoothGattCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);

                    if (bluetoothGattDescriptor == null) {
                        //adding notification descriptor if not present
                        bluetoothGattDescriptor = new BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID, 0);
                        bluetoothGattCharacteristic.addDescriptor(bluetoothGattDescriptor);
                    }
                    return bluetoothGattDescriptor;
                });
    }

    @NonNull
    private Observable<byte[]> observeOnCharacteristicChangeNotificationCallbacks(UUID characteristicUuid) {
        return characteristicNotificationSources.get(characteristicUuid);
    }

    @NonNull
    private Observable<byte[]> observeOnCharacteristicChangeIndicationCallbacks(UUID characteristicUuid) {
        return characteristicIndicationSources.get(characteristicUuid);
    }

    private void setCharacteristicIndication(UUID notificationCharacteristicUUID, boolean enable) {
        writeCharacteristic(notificationCharacteristicUUID, new byte[]{(byte) (enable ? 1 : 0)}).subscribe();
    }

    private void setCharacteristicNotification(UUID notificationCharacteristicUUID, boolean enable) {
        writeCharacteristic(notificationCharacteristicUUID, new byte[]{(byte) (enable ? 1 : 0)}).subscribe();
    }

    @NonNull
    private Observable<byte[]> setupCharacteristicIndication(BluetoothGattDescriptor bluetoothGattDescriptor, boolean enabled) {
        BluetoothGattCharacteristic bluetoothGattCharacteristic = bluetoothGattDescriptor.getCharacteristic();
        setCharacteristicIndication(bluetoothGattCharacteristic.getUuid(), true);
        return writeDescriptor(bluetoothGattDescriptor, enabled ? ENABLE_INDICATION_VALUE : DISABLE_NOTIFICATION_VALUE)
                .map(bluetoothGattDescriptorPair -> bluetoothGattDescriptorPair.second);
    }

    @NonNull
    private Observable<byte[]> setupCharacteristicNotification(BluetoothGattDescriptor bluetoothGattDescriptor, boolean enabled) {
        BluetoothGattCharacteristic bluetoothGattCharacteristic = bluetoothGattDescriptor.getCharacteristic();
        setCharacteristicNotification(bluetoothGattCharacteristic.getUuid(), true);
        return writeDescriptor(bluetoothGattDescriptor, enabled ? ENABLE_NOTIFICATION_VALUE : DISABLE_NOTIFICATION_VALUE)
                .map(bluetoothGattDescriptorPair -> bluetoothGattDescriptorPair.second);
    }

    private Observable<Pair<BluetoothGattDescriptor, byte[]>> writeDescriptor(BluetoothGattDescriptor bluetoothGattDescriptor,
                                                                              byte[] data) {
        return Observable.just(new Pair<>(bluetoothGattDescriptor, data));
    }
}
