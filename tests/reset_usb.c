#include <stdio.h>
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <IOKit/IOCFPlugIn.h>
#include <IOKit/usb/IOUSBLib.h>

int main() {
    CFMutableDictionaryRef matchingDict = IOServiceMatching("IOUSBHostDevice");
    if (!matchingDict) {
        printf("Failed to create matching dictionary\n");
        return 1;
    }
    
    SInt32 idVendor = 0x18d1;
    CFNumberRef numberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &idVendor);
    CFDictionarySetValue(matchingDict, CFSTR(kUSBVendorID), numberRef);
    CFRelease(numberRef);
    
    io_iterator_t iterator;
    kern_return_t kr = IOServiceGetMatchingServices(kIOMainPortDefault, matchingDict, &iterator);
    if (kr != KERN_SUCCESS) {
        printf("IOServiceGetMatchingServices failed: 0x%x\n", kr);
        return 1;
    }
    
    io_service_t usbDevice;
    int found = 0;
    while ((usbDevice = IOIteratorNext(iterator))) {
        IOCFPlugInInterface** plugInInterface = NULL;
        SInt32 score;
        kr = IOCreatePlugInInterfaceForService(usbDevice, kIOUSBDeviceUserClientTypeID,
                                               kIOCFPlugInInterfaceID, &plugInInterface, &score);
        if (kr == KERN_SUCCESS && plugInInterface) {
            IOUSBDeviceInterface** dev = NULL;
            kr = (*plugInInterface)->QueryInterface(plugInInterface,
                                                    CFUUIDGetUUIDBytes(kIOUSBDeviceInterfaceID),
                                                    (void**)&dev);
            (*plugInInterface)->Release(plugInInterface);
            if (kr == KERN_SUCCESS && dev) {
                printf("Found Google USB device! Resetting device...\n");
                kr = (*dev)->ResetDevice(dev);
                printf("ResetDevice returned: 0x%x\n", kr);
                (*dev)->Release(dev);
                found++;
            }
        }
        IOObjectRelease(usbDevice);
    }
    IOObjectRelease(iterator);
    printf("Finished. Devices reset: %d\n", found);
    return 0;
}
