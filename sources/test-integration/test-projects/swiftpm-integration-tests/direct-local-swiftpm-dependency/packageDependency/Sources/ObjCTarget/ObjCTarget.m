#import "ObjCTarget.h"
#import <stdio.h>

@implementation ObjCTarget

- (void) doSomethingObjC {
    printf("%s", "Hello from ObjC\n");
    fflush(stdout);
}

@end
