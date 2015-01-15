////
//  Immobilize
//
#import "CDVLocation.h"
#import "Immobilize.h"
#import <Cordova/CDVJSON.h>
#import <Cordova/CDV.h>


@implementation Immobilize {
    BOOL isDebugging;
    BOOL enabled;
    BOOL isUpdatingLocation;
    BOOL isWatchingLocation;
    BOOL stopOnTerminate;
    
    NSString *token;
    NSString *url;
    NSString *apiToken;
    NSString *watchUrl;
    NSString *watchApiToken;
    UIBackgroundTaskIdentifier bgTask;
    NSDate *lastBgTaskAt;
    
    NSError *locationError;
    
    BOOL isMoving;
    
    NSNumber *maxBackgroundHours;
    CLLocationManager *locationManager;
    UILocalNotification *localNotification;
    
    CDVLocationData *locationData;
    CLLocation *lastLocation;
    CLLocation *lastUpdateLocation;
    CLLocation *startedWatchLocation;
    NSDate *startedWatchTime;
    NSMutableArray *locationQueue;
    
    NSDate *suspendedAt;
    
    NSInteger locationAcquisitionAttempts;
    
    BOOL isAcquiringStationaryLocation;
    NSInteger maxStationaryLocationAttempts;
    
    BOOL isAcquiringSpeed;
    NSInteger maxSpeedAcquistionAttempts;
    
    NSInteger distanceFilter;
    NSInteger watchDistanceFilter;
    NSInteger locationTimeout;
    NSInteger watchLocationTimeout;
    NSInteger desiredAccuracy;
    NSInteger watchDesiredAccuracy;
    NSMutableArray *params;
    NSMutableArray *headers;
    
    BOOL isUpdateEnabled;
    BOOL isWatchEnabled;
    BOOL immobilizeReported;
}

@synthesize syncCallbackId;

- (void)pluginInitialize
{
    // background location cache, for when no network is detected.
    locationManager = [[CLLocationManager alloc] init];
    locationManager.delegate = self;
    
    localNotification = [[UILocalNotification alloc] init];
    localNotification.timeZone = [NSTimeZone defaultTimeZone];
    
    locationQueue = [[NSMutableArray alloc] init];
    
    isMoving = NO;
    isUpdatingLocation = NO;
    stopOnTerminate = NO;
    isDebugging = YES;
    isUpdateEnabled = NO;
    isWatchEnabled = NO;
    immobilizeReported = NO;
    
    maxStationaryLocationAttempts   = 4;
    maxSpeedAcquistionAttempts      = 3;
    
    bgTask = UIBackgroundTaskInvalid;
    
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onSuspend:) name:UIApplicationDidEnterBackgroundNotification object:nil];
    
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onResume:) name:UIApplicationWillEnterForegroundNotification object:nil];
    
}

- (void) update:(CDVInvokedUrlCommand*)command
{
    
	url = [command.arguments objectAtIndex: 1];
	apiToken = [command.arguments objectAtIndex: 2];
    distanceFilter      = [[command.arguments objectAtIndex: 0] intValue];
    locationTimeout     = 15;
    desiredAccuracy     = [self decodeDesiredAccuracy: 10];
    
    if(!isUpdateEnabled && !isWatchEnabled){
        
        self.syncCallbackId = command.callbackId;
        
        locationManager.pausesLocationUpdatesAutomatically = YES;
        locationManager.distanceFilter = distanceFilter; // meters
        locationManager.desiredAccuracy = desiredAccuracy;
        
        NSLog(@"  - url: %@", url);
        NSLog(@"  - distanceFilter: %ld", (long)distanceFilter);
        NSLog(@"  - locationTimeout: %ld", (long)locationTimeout);
        NSLog(@"  - desiredAccuracy: %ld", (long)desiredAccuracy);
        
        [self start];
    }
    
    isUpdateEnabled = YES;
}

- (void) watchImmobilise:(CDVInvokedUrlCommand*)command
{
    
    watchUrl = [command.arguments objectAtIndex: 2];
    watchApiToken = [command.arguments objectAtIndex: 3];
    watchDistanceFilter      = [[command.arguments objectAtIndex: 0] intValue];
    watchLocationTimeout     = [[command.arguments objectAtIndex: 1] intValue];
    watchDesiredAccuracy     = [self decodeDesiredAccuracy: 10];
    
    if(!isWatchEnabled && !isUpdateEnabled){
        
    	self.syncCallbackId = command.callbackId;
        
        locationManager.pausesLocationUpdatesAutomatically = YES;
        locationManager.distanceFilter = watchDistanceFilter; // meters
        locationManager.desiredAccuracy = watchDesiredAccuracy;
        
        NSLog(@"  - watchUrl: %@", watchUrl);
        NSLog(@"  - watchDistanceFilter: %ld", (long)watchDistanceFilter);
        NSLog(@"  - watchLocationTimeout: %ld", (long)watchLocationTimeout);
        NSLog(@"  - watchDesiredAccuracy: %ld", (long)watchDesiredAccuracy);
        
        [self start];
    }
    
    isWatchEnabled = YES;
}

- (void) flushQueue
{
    // Sanity-check the duration of last bgTask:  If greater than 30s, kill it.
    if (bgTask != UIBackgroundTaskInvalid) {
        if (-[lastBgTaskAt timeIntervalSinceNow] > 30.0) {
            NSLog(@"- Immobilize#flushQueue has to kill an out-standing background-task!");
            if (isDebugging) {
                [self notify:@"Outstanding bg-task was force-killed"];
            }
            [self stopBackgroundTask];
        }
        return;
    }
    if ([locationQueue count] > 0) {
        NSMutableDictionary *data = [locationQueue lastObject];
        [locationQueue removeObject:data];
        
        // Create a background-task and delegate to Javascript for syncing location
        bgTask = [self createBackgroundTask];
        [self.commandDelegate runInBackground:^{
            [self sync:data];
        }];
    }
}

-(NSInteger)decodeDesiredAccuracy:(NSInteger)accuracy
{
    switch (accuracy) {
        case 1000:
            accuracy = kCLLocationAccuracyKilometer;
            break;
        case 100:
            accuracy = kCLLocationAccuracyHundredMeters;
            break;
        case 10:
            accuracy = kCLLocationAccuracyNearestTenMeters;
            break;
        case 0:
            accuracy = kCLLocationAccuracyBest;
            break;
        default:
            accuracy = kCLLocationAccuracyHundredMeters;
    }
    return accuracy;
}

/**
 * Turn on background geolocation
 */
- (void) start
{
    enabled = YES;
    UIApplicationState state = [[UIApplication sharedApplication] applicationState];
    
    NSLog(@"- Immobilize start (background? %d)", state);
    
    [locationManager startMonitoringSignificantLocationChanges];
    if (state == UIApplicationStateBackground) {
        [self setPace:isMoving];
    }
}

- (void) stopUpdate:(CDVInvokedUrlCommand*)command
{
    NSLog(@"- Immobilize stop update");
    enabled = NO;
    isMoving = NO;
    isUpdateEnabled = NO;
    isWatchEnabled = NO;
    
    if(!isWatchEnabled && !isUpdateEnabled){
	    [self stopUpdatingLocation];
	    [locationManager stopMonitoringSignificantLocationChanges];
    }
    CDVPluginResult* result = nil;
    result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    
}

- (void) stopWatch:(CDVInvokedUrlCommand*)command
{
    NSLog(@"- Immobilize stop watch");
    enabled = NO;
    isMoving = NO;
    isWatchEnabled = NO;
    
    if(!isWatchEnabled && !isUpdateEnabled){
	    [self stopUpdatingLocation];
	    [locationManager stopMonitoringSignificantLocationChanges];
    }
    CDVPluginResult* result = nil;
    result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    
}

/**
 * Change pace to moving/stopped
 * @param {Boolean} isMoving
 */
- (void) onPaceChange:(CDVInvokedUrlCommand *)command
{
    isMoving = [[command.arguments objectAtIndex: 0] boolValue];
    NSLog(@"- Immobilize onPaceChange %d", isMoving);
    UIApplicationState state = [[UIApplication sharedApplication] applicationState];
    if (state == UIApplicationStateBackground) {
        [self setPace:isMoving];
    }
}

/**
 * toggle passive or aggressive location services
 */
- (void)setPace:(BOOL)value
{
    isMoving                        = value;
    isAcquiringStationaryLocation   = NO;
    isAcquiringSpeed                = NO;
    locationAcquisitionAttempts     = 0;
    
    if (isMoving) {
        isAcquiringSpeed = YES;
    } else {
        isAcquiringStationaryLocation   = YES;
    }
    if (isAcquiringSpeed || isAcquiringStationaryLocation) {
        // Crank up the GPS power temporarily to get a good fix on our current location
        [self stopUpdatingLocation];
        locationManager.distanceFilter = kCLDistanceFilterNone;
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation;
        [self startUpdatingLocation];
    }
}

-(NSMutableDictionary*) locationToHash:(CLLocation*)location
{
    NSMutableDictionary *returnInfo;
    returnInfo = [NSMutableDictionary dictionaryWithCapacity:10];
    
    NSNumber* timestamp = [NSNumber numberWithDouble:([location.timestamp timeIntervalSince1970] * 1000)];
    [returnInfo setObject:timestamp forKey:@"timestamp"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.speed] forKey:@"speed"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.verticalAccuracy] forKey:@"altitudeAccuracy"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.horizontalAccuracy] forKey:@"accuracy"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.course] forKey:@"heading"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.altitude] forKey:@"altitude"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.coordinate.latitude] forKey:@"latitude"];
    [returnInfo setObject:[NSNumber numberWithDouble:location.coordinate.longitude] forKey:@"longitude"];
    
    return returnInfo;
}
/**
 * Called by js to signify the end of a background-geolocation event
 */
-(void) finish:(CDVInvokedUrlCommand*)command
{
    NSLog(@"- Immobilize finish");
    [self stopBackgroundTask];
}

/**
 * Suspend.  Turn on passive location services
 */
-(void) onSuspend:(NSNotification *) notification
{
    NSLog(@"- Immobilize suspend (enabled? %d)", enabled);
    suspendedAt = [NSDate date];
    
    if (enabled) {
        [self setPace: isMoving];
    }
}
/**@
 * Resume.  Turn background off
 */
-(void) onResume:(NSNotification *) notification
{
    NSLog(@"- Immobilize resume");
    if (enabled) {
        [self stopUpdatingLocation];
    }
}

/**@
 * Termination. Checks to see if it should turn off
 */
-(void) onAppTerminate
{
    NSLog(@"- Immobilize appTerminate");
    if (enabled && stopOnTerminate) {
        NSLog(@"- Immobilize stoping on terminate");
        
        enabled = NO;
        isMoving = NO;
        isUpdateEnabled = NO;
        isWatchEnabled = NO;
        
        [self stopUpdatingLocation];
        [locationManager stopMonitoringSignificantLocationChanges];
    }
}


-(void) locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray *)locations
{
    NSLog(@"- Immobilize didUpdateLocations (isMoving: %d)", isMoving);
    
    locationError = nil;
    if (isMoving && !isUpdatingLocation && !isWatchingLocation) {
        [self startUpdatingLocation];
    }
    
    CLLocation *location = [locations lastObject];
    
    NSLog(@"- Immobilize didUpdateLocations (location latitude: %g)", location.coordinate.latitude);
    NSLog(@"- Immobilize didUpdateLocations (location longitude: %g)", location.coordinate.longitude);
    
    if (!isMoving && !isAcquiringStationaryLocation) {
        // Perhaps our GPS signal was interupted, re-acquire a stationaryLocation now.
        [self setPace: NO];
    }
    
    // test the age of the location measurement to determine if the measurement is cached
    // in most cases you will not want to rely on cached measurements
    if ([self locationAge:location] > 5.0) return;
    
    // test that the horizontal accuracy does not indicate an invalid measurement
    if (location.horizontalAccuracy < 0) return;
    
    if (lastUpdateLocation == NULL){
        lastUpdateLocation = location;
    }
    
    CLLocationDistance distance = [location distanceFromLocation:lastUpdateLocation];
    CLLocationDistance watchDistance;
    if(startedWatchLocation != NULL){
    	watchDistance = [location distanceFromLocation:startedWatchLocation];
    }else{
    	watchDistance = [location distanceFromLocation:lastLocation];
    }
    
    NSLog(@"- Immobilize (distance: %f)", distance);
    NSLog(@"- Immobilize (watchDistance: %f)", watchDistance);
    if(isUpdateEnabled){
	    if(lastUpdateLocation == NULL || distance > distanceFilter){
	        NSURL *nurl = [NSURL URLWithString:url];
	        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:nurl];
	        [req setHTTPMethod:@"POST"];
	        [req setValue:@"application/json" forHTTPHeaderField:@"Content-type"];
	        [req setValue:apiToken forHTTPHeaderField:@"access_token"];
	        [req setValue:@"Cache-Control" forHTTPHeaderField:@"no-cache"];
	        NSString *dataString = @"{\"latitude\":";
	        NSNumber *latitudeNumber = [NSNumber numberWithDouble:location.coordinate.latitude];
	        dataString = [dataString stringByAppendingString:[latitudeNumber stringValue]];
	        dataString = [dataString stringByAppendingString:@",\"longitude\":"];
	        NSNumber *longitudeNumber = [NSNumber numberWithDouble:location.coordinate.longitude];
	        dataString = [dataString stringByAppendingString:[longitudeNumber stringValue]];
	        dataString = [dataString stringByAppendingString:@"}"];
	        NSLog(@"- Sending %@", dataString);
	        //NSData *data = [dataString dataUsingEncoding:NSUTF8StringEncoding];
	        //NSMutableData *body = [data mutableCopy];
	        [req setHTTPBody:[dataString dataUsingEncoding:NSUTF8StringEncoding]];
	        NSHTTPURLResponse __autoreleasing *response;
	        NSError __autoreleasing *error;
	        [NSURLConnection sendSynchronousRequest:req returningResponse:&response error:&error];
	        if (error == nil && response.statusCode == 200) {
	            NSLog(@"- Immobilize SUCCESS RESPONSE");
	        } else {
	            NSLog(@"- Immobilize ERROR RESPONSE: %@",error.localizedDescription);
	        }
	        lastUpdateLocation = location;
	    }
    }
    if(isWatchEnabled){
    	
    	if(watchDistance < watchDistanceFilter){
    	
    	    if (startedWatchLocation == NULL){
                startedWatchLocation = lastLocation;
            }
            if (startedWatchTime == NULL){
                startedWatchTime = [[NSDate alloc] init];
            }
            
            NSDate *today=[NSDate date];
            NSTimeInterval dateInSecs = [today timeIntervalSinceReferenceDate];
            NSNumber *dateInSecsNumber = [NSNumber numberWithDouble:dateInSecs];
            double dateInSecsDouble = [dateInSecsNumber doubleValue];
            
            NSTimeInterval startedWatchInSecs = [startedWatchTime timeIntervalSinceReferenceDate];
            NSNumber *startedWatchInSecsNumber = [NSNumber numberWithDouble:startedWatchInSecs];
            double startedWatchInSecsDouble = [startedWatchInSecsNumber doubleValue];
            
            double timeDifference = dateInSecsDouble - startedWatchInSecsDouble;
	    	if(timeDifference > watchLocationTimeout && !immobilizeReported){
	    	immobilizeReported = YES;
	        NSURL *nurl = [NSURL URLWithString:watchUrl];
	        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:nurl];
	        [req setHTTPMethod:@"POST"];
	        [req setValue:@"application/json" forHTTPHeaderField:@"Content-type"];
	        [req setValue:watchApiToken forHTTPHeaderField:@"access_token"];
	        [req setValue:@"Cache-Control" forHTTPHeaderField:@"no-cache"];
	        NSString *dataString = @"{\"latitude\":";
	        NSNumber *latitudeNumber = [NSNumber numberWithDouble:location.coordinate.latitude];
	        dataString = [dataString stringByAppendingString:[latitudeNumber stringValue]];
	        dataString = [dataString stringByAppendingString:@",\"longitude\":"];
	        NSNumber *longitudeNumber = [NSNumber numberWithDouble:location.coordinate.longitude];
	        dataString = [dataString stringByAppendingString:[longitudeNumber stringValue]];
	        dataString = [dataString stringByAppendingString:@",\"accuracy\":"];
	        NSNumber *accuracyNumber = [NSNumber numberWithDouble:location.horizontalAccuracy];
	        dataString = [dataString stringByAppendingString:[accuracyNumber stringValue]];
	        dataString = [dataString stringByAppendingString:@"}"];
	        NSLog(@"- Sending %@", dataString);
	        //NSData *data = [dataString dataUsingEncoding:NSUTF8StringEncoding];
	        //NSMutableData *body = [data mutableCopy];
	        [req setHTTPBody:[dataString dataUsingEncoding:NSUTF8StringEncoding]];
	        NSHTTPURLResponse __autoreleasing *response;
	        NSError __autoreleasing *error;
	        [NSURLConnection sendSynchronousRequest:req returningResponse:&response error:&error];
	        if (error == nil && response.statusCode == 200) {
	            NSLog(@"- Immobilize SUCCESS RESPONSE");
	        } else {
	            NSLog(@"- Immobilize ERROR RESPONSE: %@",error.localizedDescription);
	        }
	    	}
    	}else{
    	immobilizeReported = NO;
    	startedWatchLocation = NULL;
    	startedWatchTime = NULL;
    	}
    }
    
    lastLocation = location;
    
    if (!isMoving) {
        [self setPace:YES];
    }
    [self queue:location type:@"current"];
}

-(void) queue:(CLLocation*)location type:(id)type
{
    NSLog(@"- Immobilize queue %@", type);
    NSMutableDictionary *data = [self locationToHash:location];
    [data setObject:type forKey:@"location_type"];
    [locationQueue addObject:data];
    [self flushQueue];
}

-(UIBackgroundTaskIdentifier) createBackgroundTask
{
    lastBgTaskAt = [NSDate date];
    return [[UIApplication sharedApplication] beginBackgroundTaskWithExpirationHandler:^{
        [self stopBackgroundTask];
    }];
}

/**
 * We are running in the background if this is being executed.
 * We can't assume normal network access.
 * bgTask is defined as an instance variable of type UIBackgroundTaskIdentifier
 */
-(void) sync:(NSMutableDictionary*)data
{
    NSLog(@"- Immobilize#sync");
    NSLog(@"  type: %@, position: %@,%@ speed: %@", [data objectForKey:@"location_type"], [data objectForKey:@"latitude"], [data objectForKey:@"longitude"], [data objectForKey:@"speed"]);
    if (isDebugging) {
        [self notify:[NSString stringWithFormat:@"Location update: %s\nSPD: %0.0f | DF: %ld | ACY: %0.0f",
                      ((isMoving) ? "MOVING" : "STATIONARY"),
                      [[data objectForKey:@"speed"] doubleValue],
                      (long) locationManager.distanceFilter,
                      [[data objectForKey:@"accuracy"] doubleValue]]];
        
    }
    
    // Build a resultset for javascript callback.
    NSString *locationType = [data objectForKey:@"location_type"];
    if ([locationType isEqualToString:@"current"]) {
        CDVPluginResult* result = nil;
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:data];
        [result setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:result callbackId:self.syncCallbackId];
    } else {
        NSLog(@"- Immobilize#sync could not determine location_type.");
        [self stopBackgroundTask];
    }
}


- (bool) stationaryRegionContainsLocation:(CLLocation*)location {
    return NO;
}
- (void) stopBackgroundTask
{
    UIApplication *app = [UIApplication sharedApplication];
    NSLog(@"- Immobilize stopBackgroundTask (remaining t: %f)", app.backgroundTimeRemaining);
    if (bgTask != UIBackgroundTaskInvalid)
    {
        [app endBackgroundTask:bgTask];
        bgTask = UIBackgroundTaskInvalid;
    }
    [self flushQueue];
}
/**
 * Called when user exits their stationary radius (ie: they walked ~50m away from their last recorded location.
 * - turn on more aggressive location monitoring.
 */
- (void)locationManager:(CLLocationManager *)manager didExitRegion:(CLRegion *)region
{
    NSLog(@"- Immobilize exit region");
    if (isDebugging) {
        [self notify:@"Exit stationary region"];
    }
    [self setPace:YES];
}

/**
 * 1. turn off std location services
 * 2. turn on significantChanges API
 * 3. create a region and start monitoring exits.
 */
- (void)locationManagerDidPauseLocationUpdates:(CLLocationManager *)manager
{
    NSLog(@"- Immobilize paused location updates");
    if (isDebugging) {
        [self notify:@"Stop detected"];
    }
    if (locationError) {
        isMoving = NO;
        [self stopUpdatingLocation];
    } else {
        [self setPace:NO];
    }
}

/**
 * 1. Turn off significantChanges ApI
 * 2. turn on std. location services
 * 3. nullify stationaryRegion
 */
- (void)locationManagerDidResumeLocationUpdates:(CLLocationManager *)manager
{
    NSLog(@"- Immobilize resume location updates");
    if (isDebugging) {
        [self notify:@"Resume location updates"];
    }
    [self setPace:YES];
}

- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error
{
    NSLog(@"- Immobilize locationManager failed:  %@", error);
    if (isDebugging) {
        [self notify:[NSString stringWithFormat:@"Location error: %@", error.localizedDescription]];
    }
    
    locationError = error;
    
    switch(error.code) {
        case kCLErrorLocationUnknown:
        case kCLErrorNetwork:
        case kCLErrorRegionMonitoringDenied:
        case kCLErrorRegionMonitoringSetupDelayed:
        case kCLErrorRegionMonitoringResponseDelayed:
        case kCLErrorGeocodeFoundNoResult:
        case kCLErrorGeocodeFoundPartialResult:
        case kCLErrorGeocodeCanceled:
            break;
        case kCLErrorDenied:
            [self stopUpdatingLocation];
            break;
        default:
            [self stopUpdatingLocation];
    }
}

- (void) stopUpdatingLocation
{
    [locationManager stopUpdatingLocation];
    isUpdatingLocation = NO;
}

- (void) startUpdatingLocation
{
    SEL requestSelector = NSSelectorFromString(@"requestAlwaysAuthorization");
    if ([CLLocationManager authorizationStatus] == kCLAuthorizationStatusNotDetermined && [locationManager respondsToSelector:requestSelector]) {
        ((void (*)(id, SEL))[locationManager methodForSelector:requestSelector])(locationManager, requestSelector);
        [locationManager startUpdatingLocation];
        isUpdatingLocation = YES;
    } else {
        [locationManager startUpdatingLocation];
        isUpdatingLocation = YES;
    }
}
- (void) locationManager:(CLLocationManager *)manager didChangeAuthorizationStatus:(CLAuthorizationStatus)status
{
    NSLog(@"- Immobilize didChangeAuthorizationStatus %u", status);
    if (isDebugging) {
        [self notify:[NSString stringWithFormat:@"Authorization status changed %u", status]];
    }
}

- (NSTimeInterval) locationAge:(CLLocation*)location
{
    return -[location.timestamp timeIntervalSinceNow];
}

- (void) notify:(NSString*)message
{
    localNotification.fireDate = [NSDate date];
    localNotification.alertBody = message;
    [[UIApplication sharedApplication] scheduleLocalNotification:localNotification];
}
/**
 * If you don't stopMonitoring when application terminates, the app will be awoken still when a
 * new location arrives, essentially monitoring the user's location even when they've killed the app.
 * Might be desirable in certain apps.
 */
- (void)applicationWillTerminate:(UIApplication *)application {
    [locationManager stopMonitoringSignificantLocationChanges];
    [locationManager stopUpdatingLocation];
}

- (void)dealloc
{
    locationManager.delegate = nil;
}

@end
