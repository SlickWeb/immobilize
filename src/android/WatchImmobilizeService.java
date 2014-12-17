package au.com.cathis.plugin.message.immobilize;

import android.annotation.TargetApi;
import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.util.Log;
import au.com.cathis.plugin.message.immobilize.data.DAOFactory;
import au.com.cathis.plugin.message.immobilize.data.LocationDAO;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.Iterator;

public class WatchImmobilizeService extends Service implements LocationListener {

    private static final String TAG = "WatchImmobilizeService";
    private static final String WATCH_LOCATION_UPDATE_ACTION = "au.com.cathis.plugin.message.immobilize.WATCH_LOCATION_UPDATE_ACTION";

    private PowerManager.WakeLock wakeLock;
    private Location lastLocation;

    private JSONObject params;
    private JSONObject headers;
    private String url;

    private PendingIntent singleUpdatePI;

    private Integer desiredAccuracy = 100;
    private Integer distanceFilter = 0;
    private Integer locationTimeout = 15;
    private Integer currentLocationTimeout = 15;
    private Integer immobilizeDuration = 60;
    private Integer movingAccuracy = 50;
    private String notificationTitle = "GPS Location Monitoring";
    private String notificationText = "ACTIVE";

    private Long startedImmobilizeTime;
    private Location startedImmobilizeLocation;
    private boolean immobilizeReported;

    private Criteria criteria;

    private LocationManager locationManager;
    private ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;

    @Override
    /**
     * Required but not used.
     */
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "OnBind" + intent);
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");

        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);

        singleUpdatePI = PendingIntent.getBroadcast(this, 0, new Intent(WATCH_LOCATION_UPDATE_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(watchReceiver, new IntentFilter(WATCH_LOCATION_UPDATE_ACTION));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        wakeLock.acquire();

        // Location criteria
        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(false);
        criteria.setCostAllowed(true);
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setHorizontalAccuracy(translateDesiredAccuracy(desiredAccuracy));
        criteria.setPowerRequirement(Criteria.POWER_HIGH);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        if (intent != null) {
            try {
                params = new JSONObject(intent.getStringExtra("params"));
                headers = new JSONObject(intent.getStringExtra("headers"));
            } catch (JSONException e) {
                Log.e(TAG,e.getMessage());
                e.printStackTrace();
            }
            url = intent.getStringExtra("url");
            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));
            immobilizeDuration = Integer.parseInt(intent.getStringExtra("immobilizeDuration"));
            movingAccuracy = Integer.parseInt(intent.getStringExtra("movingAccuracy"));
            locationTimeout = Integer.parseInt(intent.getStringExtra("locationTimeout"));
            currentLocationTimeout = locationTimeout;
            notificationTitle = intent.getStringExtra("notificationTitle");
            notificationText = intent.getStringExtra("notificationText");

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
        Log.i(TAG, "- url: " + url);
        Log.i(TAG, "- params: " + params.toString());
        Log.i(TAG, "- headers: " + headers.toString());
        Log.i(TAG, "- distanceFilter: " + distanceFilter);
        Log.i(TAG, "- desiredAccuracy: " + desiredAccuracy);
        Log.i(TAG, "- locationTimeout: " + locationTimeout);
        Log.i(TAG, "- notificationTitle: " + notificationTitle);
        Log.i(TAG, "- notificationText: " + notificationText);

        this.requestUpdates();

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

    private void requestUpdates() {

        locationManager.removeUpdates(this);
        locationManager.requestLocationUpdates(locationManager.GPS_PROVIDER, currentLocationTimeout * 1000, distanceFilter, this);
    }

    /**
     * Translates a number representing desired accuracy of GeoLocation system from set [0, 10, 100, 1000].
     * 0:  most aggressive, most accurate, worst battery drain
     * 1000:  least aggressive, least accurate, best for battery.
     */
    private Integer translateDesiredAccuracy(Integer accuracy) {
        switch (accuracy) {
            case 1000:
                accuracy = Criteria.ACCURACY_LOW;
                break;
            case 100:
                accuracy = Criteria.ACCURACY_MEDIUM;
                break;
            case 10:
                accuracy = Criteria.ACCURACY_HIGH;
                break;
            case 0:
                accuracy = Criteria.ACCURACY_HIGH;
                break;
            default:
                accuracy = Criteria.ACCURACY_MEDIUM;
        }
        return accuracy;
    }

    public void onLocationChanged(Location location) {
        Log.d(TAG, "- onLocationChanged: " + location.getLatitude() + "," + location.getLongitude() + ", accuracy: " + location.getAccuracy()  + ", speed: " + location.getSpeed());

        if(lastLocation != null){

            float distance;
            if(startedImmobilizeLocation != null){
                distance = location.distanceTo(startedImmobilizeLocation);
            }else{
                distance = location.distanceTo(lastLocation);
            }
            Log.d(TAG, "Distance to lastLocation: " + distance);
            Log.d(TAG, "movingAccuracy: " + movingAccuracy);
            Log.d(TAG, "distance < movingAccuracy: " + (distance < movingAccuracy));

            if ( distance < movingAccuracy ) {

                if(startedImmobilizeLocation == null)
                    startedImmobilizeLocation = lastLocation;

                if(startedImmobilizeTime == null)
                    startedImmobilizeTime = lastLocation.getTime();

                Log.d(TAG, "startedImmobilizeTime: " + new Date(startedImmobilizeTime));

                long locationTimeMinusImmobilizeDuration = location.getTime() - (immobilizeDuration*1000);
                Log.d(TAG, "locationTimeMinusImmobilizeDuration: " + new Date(locationTimeMinusImmobilizeDuration));
                Log.d(TAG, "startedImmobilizeTime <= locationTimeMinusImmobilizeDuration: " + (startedImmobilizeTime <= locationTimeMinusImmobilizeDuration));

                if( locationTimeMinusImmobilizeDuration >= startedImmobilizeTime && !immobilizeReported ){
                    immobilizeReported = true;
                    persistLocation(location);
                    if (this.isNetworkConnected()) {
                        Log.d(TAG, "Scheduling location network post");
                        try {
                            params.put("startedImmobilizeTime",startedImmobilizeTime);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        schedulePostLocations();
                    } else {
                        Log.d(TAG, "Network unavailable, waiting for now");
                    }
                }
            }else{
                immobilizeReported = false;
                startedImmobilizeTime = null;
                startedImmobilizeLocation = null;
            }
        }
        lastLocation = location;
    }

    /**
     * Broadcast receiver for receiving updates from LocationManager.
     */
    private BroadcastReceiver watchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_LOCATION_CHANGED;
            Location location = (Location) intent.getExtras().get(key);
            if (location != null) {
                Log.d(TAG, "- watchReceiver" + location.toString());
                onLocationChanged(location);
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

    private boolean postLocation(au.com.cathis.plugin.message.immobilize.data.Location l, LocationDAO dao) {
        if (l == null) {
            Log.w(TAG, "postLocation: null location");
            return false;
        }
        try {

            Log.i(TAG, "Posting  native location update: " + l);
            DefaultHttpClient httpClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(url);

            JSONObject location = new JSONObject();
            location.put("latitude", l.getLatitude());
            location.put("longitude", l.getLongitude());
            location.put("accuracy", l.getAccuracy());
            location.put("speed", l.getSpeed());
            location.put("bearing", l.getBearing());
            location.put("altitude", l.getAltitude());
            location.put("recorded_at", dao.dateToString(l.getRecordedAt()));
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

    private void persistLocation(Location location) {
        LocationDAO dao = DAOFactory.createLocationDAO(this.getApplicationContext());
        au.com.cathis.plugin.message.immobilize.data.Location savedLocation = au.com.cathis.plugin.message.immobilize.data.Location.fromAndroidLocation(location);

        if (dao.persistLocation(savedLocation)) {
            Log.d(TAG, "Persisted Location: " + savedLocation);
        } else {
            Log.w(TAG, "Failed to persist location");
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
        unregisterReceiver(watchReceiver);
        stopForeground(true);
        wakeLock.release();
    }

    private void schedulePostLocations() {
        PostLocationTask task = new WatchImmobilizeService.PostLocationTask();
        Log.d(TAG, "beforeexecute " + task.getStatus());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        else
            task.execute();
        Log.d(TAG, "afterexecute " + task.getStatus());
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        this.stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private class PostLocationTask extends AsyncTask<Object, Integer, Boolean> {

        @Override
        protected Boolean doInBackground(Object...objects) {
            Log.d(TAG, "Executing PostLocationTask#doInBackground");
            LocationDAO locationDAO = DAOFactory.createLocationDAO(WatchImmobilizeService.this.getApplicationContext());
            for (au.com.cathis.plugin.message.immobilize.data.Location savedLocation : locationDAO.getAllLocations()) {
                Log.d(TAG, "Posting saved location");
                if (postLocation(savedLocation, locationDAO)) {
                    locationDAO.deleteLocation(savedLocation);
                }
            }
            return true;
        }
        @Override
        protected void onPostExecute(Boolean result) {
            Log.d(TAG, "PostLocationTask#onPostExecture");
        }
    }
}
