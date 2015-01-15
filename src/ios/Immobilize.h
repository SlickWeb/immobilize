//
//  Immobilize.h
//

#import <Cordova/CDVPlugin.h>
#import "CDVLocation.h"

@interface Immobilize : CDVPlugin <CLLocationManagerDelegate>

@property (nonatomic, strong) NSString* syncCallbackId;

- (void) update:(CDVInvokedUrlCommand*)command;
- (void) stopUpdate:(CDVInvokedUrlCommand*)command;
- (void) watchImmobilise:(CDVInvokedUrlCommand*)command;
- (void) stopWatch:(CDVInvokedUrlCommand*)command;
- (void) finish:(CDVInvokedUrlCommand*)command;
- (void) onPaceChange:(CDVInvokedUrlCommand*)command;
- (void) onResume:(NSNotification *)notification;
- (void) onAppTerminate;

@end
