# The sensor firmware as an ESP-IDF project.
#
# It was an Arduino sketch, and the sketch had no way to ask for two Wi-Fi bands: the wrapper
# owns the radio, `WiFi.scanNetworks()` only covers the band it happens to be in, and the band
# call it does expose is only usable after the wrapper has started Wi-Fi for its own reasons.
# Under IDF the driver is ours: the band is selected with `esp_wifi_set_band_mode()` between
# scans, the scan itself is the driver's blocking call with its own timings, and BLE runs on
# NimBLE so the advertisement payload and the scan window are the numbers written here.
cmake_minimum_required(VERSION 3.16)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(signal_sensor)
