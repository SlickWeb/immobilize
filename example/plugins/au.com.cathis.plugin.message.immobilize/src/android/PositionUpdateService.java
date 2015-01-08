package au.com.cathis.plugin.message.immobilize;

import android.annotation.TargetApi;
import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.util.Log;
import android.widget.Toast;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

public class PositionUpdateService extends Service implements LocationListener {

    private static final String TAG = "PositionUpdateService";
    private static final String STATIONARY_REGION_ACTION = "au.com.cathis.plugin.message.immobilize.bgloc.STATIONARY_REGION_ACTION";
    private static final String STATIONARY_LOCATION_MONITOR_ACTION = "au.com.cathis.plugin.message.immobilize.bgloc.STATIONARY_LOCATION_MONITOR_ACTION";
    private static final String STATIONARY_ALARM_ACTION = "au.com.cathis.plugin.message.immobilize.bgloc.STATIONARY_LOCATION_MONITOR_ACTION";
    private static final String SINGLE_LOCATION_UPDATE_ACTION = "au.com.cathis.plugin.message.immobilize.bgloc.SINGLE_LOCATION_UPDATE_ACTION";
    private static final long STATIONARY_TIMEOUT = 5 * 1000 * 60;    // 5 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_LAZY = 3 * 1000 * 60;    // 3 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE = 1 * 1000 * 60;    // 1 minute.
    private static final Integer MAX_STATIONARY_ACQUISITION_ATTEMPTS = 5;
    private static final Integer MAX_SPEED_ACQUISITION_ATTEMPTS = 3;

    private PowerManager.WakeLock wakeLock;
    private android.location.Location lastLocation;
    private long lastUpdateTime = 0l;

    private JSONObject params;
    private JSONObject headers = new JSONObject();
    private String apiUrl;

    private Boolean isMoving = false;
    private Boolean isAcquiringStationaryLocation = false;
    private Boolean isAcquiringSpeed = false;
    private Integer locationAcquisitionAttempts = 0;

    private float movingAccuracy;
    private android.location.Location stationaryLocation;
    private PendingIntent stationaryAlarmPI;
    private PendingIntent stationaryLocationPollingPI;
    private long stationaryLocationPollingInterval;
    private PendingIntent stationaryRegionPI;
    private PendingIntent singleUpdatePI;
    private Integer locationTimeout = 30;

    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";

    private Criteria criteria;

    private LocationManager locationManager;
    private AlarmManager alarmManager;
    private ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "OnBind" + intent);
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");
        android.os.Debug.waitForDebugger();
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);

        // Stop-detection PI
        stationaryAlarmPI = PendingIntent.getBroadcast(this, 0, new Intent(STATIONARY_ALARM_ACTION), 0);
        registerReceiver(stationaryAlarmReceiver, new IntentFilter(STATIONARY_ALARM_ACTION));

        // Stationary region PI
        stationaryRegionPI = PendingIntent.getBroadcast(this, 0, new Intent(STATIONARY_REGION_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(stationaryRegionReceiver, new IntentFilter(STATIONARY_REGION_ACTION));

        // Stationary location monitor PI
        stationaryLocationPollingPI = PendingIntent.getBroadcast(this, 0, new Intent(STATIONARY_LOCATION_MONITOR_ACTION), 0);
        registerReceiver(stationaryLocationMonitorReceiver, new IntentFilter(STATIONARY_LOCATION_MONITOR_ACTION));

        // One-shot PI
        singleUpdatePI = PendingIntent.getBroadcast(this, 0, new Intent(SINGLE_LOCATION_UPDATE_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(singleUpdateReceiver, new IntentFilter(SINGLE_LOCATION_UPDATE_ACTION));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        wakeLock.acquire();

        // Location criteria
        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        if (intent != null) {
            try {
                params = new JSONObject(intent.getStringExtra("params"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            apiUrl = intent.getStringExtra("apiUrl");
            movingAccuracy = Integer.parseInt(intent.getStringExtra("movingAccuracy"));
            //notificationTitle = intent.getStringExtra("notificationTitle");
            //notificationText = intent.getStringExtra("notificationText");

            // Build a Notification required for running service in foreground.
            Intent main = new Intent(this, ImmobilizePlugin.class);
            main.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = new Notification.Builder(this);
            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationText);
            builder.setSmallIcon(android.R.drawable.ic_menu_mylocation);
            builder.setContentIntent(pendingIntent);
            Notification notification;
            if (Build.VERSION.SDK_INT >= 16) {
                notification = buildForegroundNotification(builder);
            } else {
                notification = buildForegroundNotificationCompat(builder);
            }
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;
            startForeground(startId, notification);
        }
        Log.i(TAG, "- apiUrl: " + apiUrl);
        Log.i(TAG, "- params: " + params.toString());
        Log.i(TAG, "- movingAccuracy: " + movingAccuracy);
        Log.i(TAG, "- notificationTitle: " + notificationTitle);
        Log.i(TAG, "- notificationText: " + notificationText);

        this.setPace(false);

        //We want this service to continue running until it is explicitly stopped
        return START_REDELIVER_INTENT;
    }

    @TargetApi(16)
    private Notification buildForegroundNotification(Notification.Builder builder) {
        return builder.build();
    }

    @SuppressWarnings("deprecation")
    @TargetApi(15)
    private Notification buildForegroundNotificationCompat(Notification.Builder builder) {
        return builder.getNotification();
    }

    @Override
    public boolean stopService(Intent intent) {
        Log.i(TAG, "- Received stop: " + intent);
        cleanUp();
        return super.stopService(intent);
    }

    /**
     * @param value set true to engage "aggressive", battery-consuming tracking, false for stationary-region tracking
     */
    private void setPace(Boolean value) {

        Log.i(TAG, "setPace: " + value);

        Boolean wasMoving = isMoving;
        isMoving = value;
        isAcquiringStationaryLocation = false;
        isAcquiringSpeed = false;
        stationaryLocation = null;

        locationManager.removeUpdates(this);

        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);
        criteria.setPowerRequirement(Criteria.POWER_HIGH);

        if (isMoving) {
            // setPace can be called while moving, after distanceFilter has been recalculated.  We don't want to re-acquire velocity in this case.
            if (!wasMoving) {
                isAcquiringSpeed = true;
            }
        } else {
            isAcquiringStationaryLocation = true;
        }

        // Temporarily turn on super-aggressive geolocation on all providers when acquiring velocity or stationary location.
        if (isAcquiringSpeed || isAcquiringStationaryLocation) {
            locationAcquisitionAttempts = 0;
            // Turn on each provider aggressively for a short period of time
            List<String> matchingProviders = locationManager.getAllProviders();
            for (String provider : matchingProviders) {
                if (provider != LocationManager.PASSIVE_PROVIDER) {
                    locationManager.requestLocationUpdates(provider, 0, 0, this);
                }
            }
        } else {
            locationManager.requestLocationUpdates(locationManager.getBestProvider(criteria, true), locationTimeout * 1000, 30, this);
        }
    }

    /**
     * Returns the most accurate and timely previously detected location.
     * Where the last result is beyond the specified maximum distance or
     * latency a one-off location update is returned via the {@link android.location.LocationListener}
     *
     * @return The most accurate and / or timely previously detected location.
     */
    public android.location.Location getLastBestLocation() {
        int minDistance = (int) movingAccuracy;
        long minTime = System.currentTimeMillis() - (locationTimeout * 1000);

        Log.i(TAG, "- fetching last best location " + minDistance + "," + minTime);
        android.location.Location bestResult = null;
        float bestAccuracy = Float.MAX_VALUE;
        long bestTime = Long.MIN_VALUE;

        // Iterate through all the providers on the system, keeping
        // note of the most accurate result within the acceptable time limit.
        // If no result is found within maxTime, return the newest Location.
        List<String> matchingProviders = locationManager.getAllProviders();
        for (String provider : matchingProviders) {
            Log.d(TAG, "- provider: " + provider);
            android.location.Location location = locationManager.getLastKnownLocation(provider);
            if (location != null) {
                Log.d(TAG, " location: " + location.getLatitude() + "," + location.getLongitude() + "," + location.getAccuracy() + "," + location.getSpeed() + "m/s");
                float accuracy = location.getAccuracy();
                long time = location.getTime();
                Log.d(TAG, "time>minTime: " + (time > minTime) + ", accuracy<bestAccuracy: " + (accuracy < bestAccuracy));
                if ((time > minTime && accuracy < bestAccuracy)) {
                    bestResult = location;
                    bestAccuracy = accuracy;
                    bestTime = time;
                }
            }
        }
        return bestResult;
    }

    public void onLocationChanged(android.location.Location location) {
        Log.d(TAG, "- onLocationChanged: " + location.getLatitude() + "," + location.getLongitude() + ", accuracy: " + location.getAccuracy() + ", isMoving: " + isMoving + ", speed: " + location.getSpeed());

        if (!isMoving) {
            // Perhaps our GPS signal was interupted, re-acquire a stationaryLocation now.
            setPace(false);
        }

        if (isAcquiringStationaryLocation) {
            if (stationaryLocation == null || stationaryLocation.getAccuracy() > location.getAccuracy()) {
                stationaryLocation = location;
            }
            if (++locationAcquisitionAttempts == MAX_STATIONARY_ACQUISITION_ATTEMPTS) {
                isAcquiringStationaryLocation = false;
                startMonitoringStationaryRegion(stationaryLocation);
            } else {
                // Unacceptable stationary-location: bail-out and wait for another.
                return;
            }
        } else if (isAcquiringSpeed) {
            if (++locationAcquisitionAttempts == MAX_SPEED_ACQUISITION_ATTEMPTS) {
                // Got enough samples, assume we're confident in reported speed now.  Play "woohoo" sound.
                isAcquiringSpeed = false;
                setPace(true);
            } else {
                return;
            }
        } else if (isMoving) {
            // Only reset stationaryAlarm when accurate speed is detected, prevents spurious locations from resetting when stopped.
            if ((location.getSpeed() >= 1) && (location.getAccuracy() <= movingAccuracy)) {
                resetStationaryAlarm();
            }
        } else if (stationaryLocation != null) {
            return;
        }
        // Go ahead and cache, push to server
        lastLocation = location;
        postLocation(location);

        if (this.isNetworkConnected()) {
            Log.d(TAG, "Scheduling location network post");
            //schedulePostLocations();
        } else {
            Log.d(TAG, "Network unavailable, sending now.");
        }
    }

    public void resetStationaryAlarm() {
        alarmManager.cancel(stationaryAlarmPI);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + STATIONARY_TIMEOUT, stationaryAlarmPI); // Millisec * Second * Minute
    }

    private void startMonitoringStationaryRegion(android.location.Location location) {
        locationManager.removeUpdates(this);
        stationaryLocation = location;

        Log.i(TAG, "- startMonitoringStationaryRegion (" + location.getLatitude() + "," + location.getLongitude() + "), accuracy:" + location.getAccuracy());

        // Here be the execution of the stationary region monitor
        locationManager.addProximityAlert(
                location.getLatitude(),
                location.getLongitude(),
                (location.getAccuracy() < movingAccuracy) ? movingAccuracy : location.getAccuracy(),
                (long) -1,
                stationaryRegionPI
        );

        startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
    }

    public void startPollingStationaryLocation(long interval) {
        // proximity-alerts don't seem to work while suspended in latest Android 4.42 (works in 4.03).  Have to use AlarmManager to sample
        //  location at regular intervals with a one-shot.
        stationaryLocationPollingInterval = interval;
        alarmManager.cancel(stationaryLocationPollingPI);
        long start = System.currentTimeMillis() + (60 * 1000);
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, start, interval, stationaryLocationPollingPI);
    }

    public void onPollStationaryLocation(android.location.Location location) {
        if (isMoving) {
            return;
        }
        float distance = abs(location.distanceTo(stationaryLocation) - stationaryLocation.getAccuracy() - location.getAccuracy());

        Log.i(TAG, "- distance from stationary location: " + distance);
        if (distance > movingAccuracy) {
            onExitStationaryRegion(location);
        } else if (distance > 0) {
            startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE);
        } else if (stationaryLocationPollingInterval != STATIONARY_LOCATION_POLLING_INTERVAL_LAZY) {
            startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
        }
    }

    /**
     * User has exit his stationary region!  Initiate aggressive geolocation!
     */
    public void onExitStationaryRegion(android.location.Location location) {

        // Cancel the periodic stationary location monitor alarm.
        alarmManager.cancel(stationaryLocationPollingPI);

        // Kill the current region-monitor we just walked out of.
        locationManager.removeProximityAlert(stationaryRegionPI);

        // Engage aggressive tracking.
        this.setPace(true);
    }

    /**
     * Broadcast receiver for receiving a single-update from LocationManager.
     */
    private BroadcastReceiver singleUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_LOCATION_CHANGED;
            android.location.Location location = (android.location.Location) intent.getExtras().get(key);
            if (location != null) {
                Log.d(TAG, "- singleUpdateReciever" + location.toString());
                onPollStationaryLocation(location);
            }
        }
    };

    /**
     * Broadcast receiver which detcts a user has stopped for a long enough time to be determined as STOPPED
     */
    private BroadcastReceiver stationaryAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "- stationaryAlarm fired");
            setPace(false);
        }
    };
    /**
     * Broadcast receiver to handle stationaryMonitor alarm, fired at low frequency while monitoring stationary-region.
     * This is required because latest Android proximity-alerts don't seem to operate while suspended.  Regularly polling
     * the location seems to trigger the proximity-alerts while suspended.
     */
    private BroadcastReceiver stationaryLocationMonitorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "- stationaryLocationMonitorReceiver fired");

            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
            criteria.setPowerRequirement(Criteria.POWER_HIGH);
            locationManager.requestSingleUpdate(criteria, singleUpdatePI);
        }
    };
    /**
     * Broadcast receiver which detects a user has exit his circular stationary-region determined by the greater of stationaryLocation.getAccuracy() OR stationaryRadius
     */
    private BroadcastReceiver stationaryRegionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "stationaryRegionReceiver");
            String key = LocationManager.KEY_PROXIMITY_ENTERING;

            Boolean entering = intent.getBooleanExtra(key, false);
            if (entering) {
                Log.d(TAG, "- ENTER");
                if (isMoving) {
                    setPace(false);
                }
            } else {
                Log.d(TAG, "- EXIT");
                // There MUST be a valid, recent location if this event-handler was called.
                android.location.Location location = getLastBestLocation();
                if (location != null) {
                    onExitStationaryRegion(location);
                }
            }
        }
    };

    public void onProviderDisabled(String provider) {
        Log.d(TAG, "- onProviderDisabled: " + provider);
    }

    public void onProviderEnabled(String provider) {
        Log.d(TAG, "- onProviderEnabled: " + provider);
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "- onStatusChanged: " + provider + ", status: " + status);
    }

    private boolean postLocation(android.location.Location loc) {

        au.com.cathis.plugin.message.immobilize.Location l = au.com.cathis.plugin.message.immobilize.Location.fromAndroidLocation(loc);

        if (l == null) {
            Log.w(TAG, "postLocation: null location");
            return false;
        }
        try {
            lastUpdateTime = SystemClock.elapsedRealtime();
            Log.i(TAG, "Posting  native location update: " + l);
            DefaultHttpClient httpClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(apiUrl);

            JSONObject location = new JSONObject();
            location.put("latitude", l.getLatitude());
            location.put("longitude", l.getLongitude());
            location.put("accuracy", l.getAccuracy());
            location.put("speed", l.getSpeed());
            location.put("bearing", l.getBearing());
            location.put("altitude", l.getAltitude());
            location.put("recorded_at", l.getRecordedAt());
            params.put("location", location);

            Log.i(TAG, "location: " + location.toString());

            StringEntity se = new StringEntity(params.toString());
            request.setEntity(se);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");

            Iterator<String> headkeys = headers.keys();
            while (headkeys.hasNext()) {
                String headkey = headkeys.next();
                if (headkey != null) {
                    Log.d(TAG, "Adding Header: " + headkey + " : " + (String) headers.getString(headkey));
                    request.setHeader(headkey, (String) headers.getString(headkey));
                }
            }
            Log.d(TAG, "Posting to " + request.getURI().toString());
            HttpResponse response = httpClient.execute(request);
            Log.i(TAG, "Response received: " + response.getStatusLine());
            if (response.getStatusLine().getStatusCode() == 200) {
                return true;
            } else {
                return false;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Exception posting location: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private boolean isNetworkConnected() {
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if (networkInfo != null) {
            Log.d(TAG, "Network found, type = " + networkInfo.getTypeName());
            return networkInfo.isConnected();
        } else {
            Log.d(TAG, "No active network info");
            return false;
        }
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "------------------------------------------ Destroyed Location update Service");
        cleanUp();
        super.onDestroy();
    }

    private void cleanUp() {
        locationManager.removeUpdates(this);
        alarmManager.cancel(stationaryAlarmPI);
        alarmManager.cancel(stationaryLocationPollingPI);

        unregisterReceiver(stationaryAlarmReceiver);
        unregisterReceiver(singleUpdateReceiver);
        unregisterReceiver(stationaryRegionReceiver);
        unregisterReceiver(stationaryLocationMonitorReceiver);

        if (stationaryLocation != null && !isMoving) {
            try {
                locationManager.removeProximityAlert(stationaryRegionPI);
            } catch (Throwable e) {
                Log.w(TAG, "- Something bad happened while removing proximity-alert");
            }
        }
        stopForeground(true);
        wakeLock.release();
    }
}
