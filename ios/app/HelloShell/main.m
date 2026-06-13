#import <UIKit/UIKit.h>
#import "AppDelegate.h"

// Dump the symbolicated stack of any uncaught ObjC exception to Documents/crash.log
// so we can see exactly which frame creates the second UIApplication (the raw
// addresses in jvm-stdio.log are unanchored).
static void HelloShellUncaughtHandler(NSException *e) {
    NSMutableString *s = [NSMutableString string];
    [s appendFormat:@"UNCAUGHT %@: %@\n", e.name, e.reason];
    for (NSString *frame in e.callStackSymbols) {
        [s appendFormat:@"%@\n", frame];
    }
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"crash.log"];
    [[s dataUsingEncoding:NSUTF8StringEncoding] writeToFile:path
        options:(NSDataWritingAtomic | NSDataWritingFileProtectionCompleteUnlessOpen) error:NULL];
}

int main(int argc, char *argv[]) {
    @autoreleasepool {
        NSSetUncaughtExceptionHandler(&HelloShellUncaughtHandler);
        return UIApplicationMain(argc, argv, nil, NSStringFromClass([AppDelegate class]));
    }
}
