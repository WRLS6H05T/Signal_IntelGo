# `main` is the one component that may include any other, so the components this file uses -
# Wi-Fi, NimBLE, NVS, UART and the USB-Serial/JTAG driver - need no REQUIRES list of their own.
idf_component_register(SRCS "main.cpp"
                       INCLUDE_DIRS ".")
