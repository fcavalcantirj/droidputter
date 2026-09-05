# R8 rules for :app (release build, proguard-android-optimize.txt + this file).
#
# Nothing here is required: every reflective surface is already covered by the consumer rules the
# libraries bundle, which R8 picks up automatically from their AARs/JARs:
#   - kotlinx-serialization 1.7.3 ships META-INF/com.android.tools/r8/*.pro keeping Companion objects
#     and serializer() methods of @Serializable classes.
#   - usb-serial-for-android 3.8.0 ships `-keep class com.hoho.android.usbserial.driver.* { *; }` for
#     the prober's reflective driver construction (UsbSerialProber / ProbeTable newInstance()).
#   - kotlinx-coroutines and Compose ship their own consumer rules.
# Every :core serializer is reached by a direct X.serializer() call (no reflection, no serializer<T>()
# lookup by class name), so the generated $serializer classes are kept by plain reachability.
#
# OPTIONAL belt-and-braces (not needed today; harmless if the serializer wiring ever changes to a
# reflective lookup):
-keep,includedescriptorclasses class com.droidputter.**$$serializer { *; }
