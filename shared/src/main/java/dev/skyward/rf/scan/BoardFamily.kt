package dev.skyward.rf.scan

/**
 * What firmware a board on a cable is running.
 *
 * It lives here rather than beside the Android USB code because the console parser in this
 * package is written in terms of it, and both the phone and the desk host have to name a board
 * the same way - a poll is a list of verbs, and the verbs depend on the family.
 */
enum class BoardFamily { MARAUDER, GHOSTESP, BISCUIT, SIGNALSENSOR, ESP32 }
