package com.polidea.rxandroidble.mockrxandroidble

import android.os.Build
import com.polidea.rxandroidble.exceptions.BleConflictingNotificationAlreadySetException
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robospock.RoboSpecification
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject

@Config(manifest = Config.NONE, constants = BuildConfig, sdk = Build.VERSION_CODES.LOLLIPOP)
public class RxBleClientMockTest extends RoboSpecification {

    def serviceUUID = UUID.fromString("00001234-0000-0000-8000-000000000000")
    def characteristicUUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    def characteristicNotifiedUUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    def characteristicData = "Polidea".getBytes()
    def descriptorUUID = UUID.fromString("00001337-0000-1000-8000-00805f9b34fb");
    def descriptorData = "Config".getBytes();
    def rxBleClient
    def PublishSubject characteristicNotificationSubject = PublishSubject.create()
    def PublishSubject characteristicIndicationSubject = PublishSubject.create()

    def setup() {
        rxBleClient = new RxBleClientMock.Builder()
                .addDevice(
                new RxBleClientMock.DeviceBuilder()
                        .deviceMacAddress("AA:BB:CC:DD:EE:FF")
                        .deviceName("TestDevice")
                        .scanRecord("ScanRecord".getBytes())
                        .rssi(42)
                        .notificationSource(characteristicNotifiedUUID, characteristicNotificationSubject)
                        .indicationSource(characteristicNotifiedUUID, characteristicIndicationSubject)
                        .addService(
                        serviceUUID,
                        new RxBleClientMock.CharacteristicsBuilder()
                                .addCharacteristic(
                                characteristicUUID,
                                characteristicData,
                                new RxBleClientMock.DescriptorsBuilder()
                                        .addDescriptor(descriptorUUID, descriptorData)
                                        .build()
                        ).build()
                ).build()
        ).build();
    }

    def "should return the BluetoothDevice name"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices()
                .take(1)
                .map { scanResult -> scanResult.getBleDevice().getName() }
                .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue("TestDevice")
    }

    def "should return the BluetoothDevice address"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices()
                .take(1)
                .map { scanResult -> scanResult.getBleDevice().getMacAddress() }
                .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue("AA:BB:CC:DD:EE:FF")
    }

    def "should return the BluetoothDevice rssi"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices()
                .take(1)
                .map { scanResult -> scanResult.getRssi() }
                .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue(42)
    }

    def "should return services list"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection ->
            rxBleConnection
                    .discoverServices()
                    .map { rxBleDeviceServices -> rxBleDeviceServices.getBluetoothGattServices() }
                    .map { servicesList -> servicesList.size() }
        }
        .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue(1)
    }

    def "should return characteristic data"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection -> rxBleConnection.readCharacteristic(characteristicUUID) }
                .map { data -> new String(data) }
                .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue("Polidea")
    }

    def "should return descriptor data"() {
        given:
        def testSubscriber = TestSubscriber.create()

        when:
        rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection -> rxBleConnection.readDescriptor(serviceUUID, characteristicUUID, descriptorUUID) }
                .map { data -> new String(data) }
                .subscribe(testSubscriber)

        then:
        testSubscriber.assertValue("Config")
    }

    def "should return notification data"() {
        given:
        def testSubscriber = TestSubscriber.create()
        rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection -> rxBleConnection.setupNotification(characteristicNotifiedUUID) }
                .subscribe { obs -> obs.map { data -> new String(data) } subscribe(testSubscriber) }

        when:
        characteristicNotificationSubject.onNext("NotificationData".getBytes())

        then:
        testSubscriber.assertValue("NotificationData")
    }

    def "should return indication data"() {
        given:
        def testSubscriber = TestSubscriber.create()
        rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection -> rxBleConnection.setupIndication(characteristicNotifiedUUID) }
                .subscribe { obs -> obs.map { data -> new String(data) } subscribe(testSubscriber) }

        when:
        characteristicIndicationSubject.onNext("IndicationData".getBytes())

        then:
        testSubscriber.assertValue("IndicationData")
    }

    def "should throw exception when trying to setup notification after indication was set before for the same characteristic"() {
        given:
        def testSubscriber = TestSubscriber.create()
        def observable = rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection ->
                    rxBleConnection.setupIndication(characteristicNotifiedUUID)
                    rxBleConnection.setupNotification(characteristicNotifiedUUID)
                }

        when:
        observable.subscribe(testSubscriber)

        then:
        testSubscriber.assertError(BleConflictingNotificationAlreadySetException)
        (testSubscriber.getOnErrorEvents().get(0) as BleConflictingNotificationAlreadySetException).indicationAlreadySet()
    }

    def "should throw exception when trying to setup indication after notification was set before for the same characteristic"() {
        given:
        def testSubscriber = TestSubscriber.create()
        def observable = rxBleClient.scanBleDevices(null)
                .take(1)
                .map { scanResult -> scanResult.getBleDevice() }
                .flatMap { rxBleDevice -> rxBleDevice.establishConnection(RuntimeEnvironment.application, false) }
                .flatMap { rxBleConnection ->
            rxBleConnection.setupNotification(characteristicNotifiedUUID)
            rxBleConnection.setupIndication(characteristicNotifiedUUID)
        }

        when:
        observable.subscribe(testSubscriber)

        then:
        testSubscriber.assertError(BleConflictingNotificationAlreadySetException)
        (testSubscriber.getOnErrorEvents().get(0) as BleConflictingNotificationAlreadySetException).notificationAlreadySet()
    }
}
