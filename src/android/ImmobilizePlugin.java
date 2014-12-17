package au.com.cathis.plugin.message.immobilize;

import android.provider.Settings;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.util.Log;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ImmobilizePlugin extends CordovaPlugin {

    private static final String TAG = "ImmobilizePlugin";

    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_STOP_UPDATE = "stopUpdate";
    public static final String ACTION_WATCH_IMMOBILISE = "watchImmobilise";
    public static final String ACTION_STOP_WATCH = "stopWatch";

    private Intent updateServiceIntent;
    private Intent watchServiceIntent;
    private Boolean isUpdateEnabled = false;
    private Boolean isWatchingEnabled = false;

    /**
     * The standard execute method that Cordova calls for every plugin when a call to executeNative is performed on the JavaScript side.
     * @param action          The action to execute.
     * @param data            The parameters for the call.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return true if an appropriate action was called.
     */
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) {

        Activity activity = this.cordova.getActivity();
        Boolean result = false;
        updateServiceIntent = new Intent(activity, PositionUpdateService.class);
        watchServiceIntent = new Intent(activity, WatchImmobilizeService.class);

        if (ACTION_UPDATE.equalsIgnoreCase(action)) {
            if(isUpdateEnabled){
                callbackContext.error("Position Updates already enabled.");
            }else{

                try{

                    JSONObject postParameters = new JSONObject();
                    postParameters.put("accessToken", data.getString(2));
                    postParameters.put("deviceId", Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID));
                    callbackContext.success();
                    updateServiceIntent.putExtra("url", data.getString(1));
                    updateServiceIntent.putExtra("params", postParameters.toString());
                    updateServiceIntent.putExtra("headers", new JSONObject().toString());
                    updateServiceIntent.putExtra("desiredAccuracy", "100");
                    updateServiceIntent.putExtra("distanceFilter", data.getString(0));
                    updateServiceIntent.putExtra("locationTimeout", "15");
                    updateServiceIntent.putExtra("notificationTitle", "GPS Location Monitoring");
                    updateServiceIntent.putExtra("notificationText", "ACTIVE");

                    activity.startService(updateServiceIntent);
                    isUpdateEnabled = true;
                    result = true;
                }catch (JSONException ex){
                    Log.e(TAG, "There was a problem creating the JSON object for the parameters: "+ex.getMessage());
                    callbackContext.error("Could not parse the provided parameters to this action. Error: "+ex.getMessage());
                }
            }

        }
        else if (ACTION_STOP_UPDATE.equalsIgnoreCase(action)) {
            isUpdateEnabled = false;
            activity.stopService(updateServiceIntent);
            result = true;
            callbackContext.success();
        }
        else if (ACTION_WATCH_IMMOBILISE.equalsIgnoreCase(action)) {
            if(isWatchingEnabled){
                callbackContext.error("Watch Immobilize already enabled.");
            }else{

                try{

                    JSONObject postParameters = new JSONObject();
                    postParameters.put("accessToken", data.getString(3));
                    postParameters.put("deviceId", Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID));
                    callbackContext.success();
                    watchServiceIntent.putExtra("url", data.getString(2));
                    watchServiceIntent.putExtra("params", postParameters.toString());
                    watchServiceIntent.putExtra("headers", new JSONObject().toString());
                    watchServiceIntent.putExtra("desiredAccuracy", "100");
                    watchServiceIntent.putExtra("distanceFilter", "0");
                    watchServiceIntent.putExtra("movingAccuracy", data.getString(0));
                    watchServiceIntent.putExtra("locationTimeout", "15");
                    watchServiceIntent.putExtra("immobilizeDuration", data.getString(1));
                    watchServiceIntent.putExtra("isDebugging", "true");
                    watchServiceIntent.putExtra("notificationTitle", "GPS Location Monitoring");
                    watchServiceIntent.putExtra("notificationText", "ACTIVE");

                    activity.startService(watchServiceIntent);
                    isWatchingEnabled = true;
                    result = true;
                }catch (JSONException ex){
                    Log.e(TAG, "There was a problem creating the JSON object for the parameters: "+ex.getMessage());
                    callbackContext.error("Could not parse the provided parameters to this action. Error: "+ex.getMessage());
                }
            }
        }
        else if (ACTION_STOP_WATCH.equalsIgnoreCase(action)) {
            isWatchingEnabled = false;
            activity.stopService(watchServiceIntent);
            result = true;
            callbackContext.success();
        }

        return result;
    }

    /**
     * Override method in CordovaPlugin.
     * Checks to see if it should turn off
     */
    public void onDestroy() {
        Activity activity = this.cordova.getActivity();
        activity.stopService(updateServiceIntent);
        activity.stopService(watchServiceIntent);
    }
}
