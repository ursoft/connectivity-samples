Трансформер протокола Schwinn 570u (изделие с BTLE-адресом 84:71:27:27:4A:44 настроено соединять автоматом) в PowerMeter ANT+ (40000) и Cadence Sensor ANT+ (40001) - в таком виде его уже понимает Zwift.
Одновременно сконвертированные данные выдаются и по протоколу BLE.
Аналог этой программы для Windows (реализован только протокол BLE) - https://github.com/ursoft/Jenx.Bluetooth.GattServer

Выжимки протокола Schwinn 570u

EventRecordDesc
1. 0509030100 - нажал start
2. 0509030300 - автопауза?
3. 0509030000 - стоп (здесь и выше первая 03 - номер юзера)
   
EventRecordDesc 3bf58980-3a2f-11e6-9011-0002a5d5c51b 5c7d82a0-9803-11e3-8a6c-0002a5d5c51b
- 00 == 11 к-во байт?
- 01 == 20
- 02 == 00
- 03 циклический счетчик 00-20-40-60-80-a0-c0-e0
- 04-06 LSB к-во оборотов
- 07 ? какое-то битовое поле, не пульс!
- 08-09 LSB к-во 1024-х секунды тренировки
- 10 ? младшая часть калорий
- 11-15 LSB какой-то еще счетчик, быстрее растет под нагрузкой - не калории ли
- 16 Уровень сложности

"Мощность" эмпирически "вычисляется" из калорий.

Нераскрытые темы:
1. Где Schwinn 570u прячет показания пульса
2. Можно ли при помощи команд по BTLE задать уровень сопротивления (судя по идентификаторам в протоколе, нет)


Android BluetoothLeGatt Sample
===================================

This sample demonstrates how to use the Bluetooth LE Generic Attribute Profile (GATT)
to transmit arbitrary data between devices.

Introduction
------------

This sample shows a list of available Bluetooth LE devices and provides
an interface to connect, display data and display GATT services and
characteristics supported by the devices.

It creates a [Service][1] for managing connection and data communication with a GATT server
hosted on a given Bluetooth LE device.

The Activities communicate with the Service, which in turn interacts with the [Bluetooth LE API][2].

[1]:http://developer.android.com/reference/android/app/Service.html
[2]:https://developer.android.com/reference/android/bluetooth/BluetoothGatt.html

Pre-requisites
--------------

- Android SDK 28
- Android Build Tools v28.0.3
- Android Support Repository

Screenshots
-------------

<img src="screenshots/1-main.png" height="400" alt="Screenshot"/> <img src="screenshots/2-detail.png" height="400" alt="Screenshot"/> 

Getting Started
---------------

This sample uses the Gradle build system. To build this project, use the
"gradlew build" command or use "Import Project" in Android Studio.

Support
-------

- Stack Overflow: http://stackoverflow.com/questions/tagged/android

If you've found an error in this sample, please file an issue:
https://github.com/android/connectivity

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub. Please see CONTRIBUTING.md for more details.
