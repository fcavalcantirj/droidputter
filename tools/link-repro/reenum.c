// Force a USB re-enumeration of the first 303A:1001 device (ESP32-S3 USB JTAG/serial) without power loss.
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <IOKit/IOKitLib.h>
#include <IOKit/usb/IOUSBLib.h>
#include <IOKit/IOCFPlugIn.h>
#include <CoreFoundation/CoreFoundation.h>
#include <string.h>
int main(int argc, char **argv) {
  int suspend_s = (argc > 2 && !strcmp(argv[1], "suspend")) ? atoi(argv[2]) : 0;
  CFMutableDictionaryRef m = IOServiceMatching(kIOUSBDeviceClassName);
  SInt32 vid = 0x303A, pid = 0x1001;
  CFDictionarySetValue(m, CFSTR(kUSBVendorID), CFNumberCreate(NULL, kCFNumberSInt32Type, &vid));
  CFDictionarySetValue(m, CFSTR(kUSBProductID), CFNumberCreate(NULL, kCFNumberSInt32Type, &pid));
  io_iterator_t it; if (IOServiceGetMatchingServices(kIOMainPortDefault, m, &it) != KERN_SUCCESS) { puts("match failed"); return 1; }
  io_service_t dev = IOIteratorNext(it); if (!dev) { puts("device not found"); return 1; }
  IOCFPlugInInterface **plug = NULL; SInt32 score = 0;
  if (IOCreatePlugInInterfaceForService(dev, kIOUSBDeviceUserClientTypeID, kIOCFPlugInInterfaceID, &plug, &score) != KERN_SUCCESS || !plug) { puts("plugin failed"); return 1; }
  IOUSBDeviceInterface **usb = NULL;
  (*plug)->QueryInterface(plug, CFUUIDGetUUIDBytes(kIOUSBDeviceInterfaceID), (LPVOID *)&usb);
  IODestroyPlugInInterface(plug);
  if (!usb) { puts("iface failed"); return 1; }
  IOReturn r = (*usb)->USBDeviceOpen(usb);
  printf("open: 0x%x\n", r);
  if (suspend_s > 0) {
    r = (*usb)->USBDeviceSuspend(usb, true); printf("suspend: 0x%x\n", r); fflush(stdout);
    sleep(suspend_s);
    r = (*usb)->USBDeviceSuspend(usb, false); printf("resume: 0x%x\n", r);
  } else {
    r = (*usb)->USBDeviceReEnumerate(usb, 0);
    printf("reenumerate: 0x%x\n", r);
  }
  (*usb)->USBDeviceClose(usb); (*usb)->Release(usb);
  return 0;
}
