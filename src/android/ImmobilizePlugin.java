package au.com.cathis.plugin.message.immobilize;

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

    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) {
        Activity activity = this.cordova.getActivity();
        Boolean result = false;
        updateServiceIntent = new Intent(activity, PositionUpdateService.class);

        if (ACTION_UPDATE.equalsIgnoreCase(action)) {
            if(!isUpdateEnabled){
                callbackContext.error("Position Updates already enabled.");
            }else{
                callbackContext.success();
                try{

                    JSONObject postParameters = new JSONObject();
                    postParameters.put("accessToken", data.getString(2));

                    updateServiceIntent.putExtra("url", data.getString(1));
                    updateServiceIntent.putExtra("params", postParameters.toString());
                    //updateServiceIntent.putExtra("headers", new JSONObject().toString());
                    //updateServiceIntent.putExtra("stationaryRadius", 50);
                    updateServiceIntent.putExtra("movingAccuracy", data.getString(0));
                    //updateServiceIntent.putExtra("distanceFilter", distanceFilter);
                    //updateServiceIntent.putExtra("locationTimeout", locationTimeout);
                    //updateServiceIntent.putExtra("isDebugging", isDebugging);
                    //updateServiceIntent.putExtra("notificationTitle", notificationTitle);
                    //updateServiceIntent.putExtra("notificationText", notificationText);
                    //updateServiceIntent.putExtra("stopOnTerminate", stopOnTerminate);

                    activity.startService(updateServiceIntent);
                    isUpdateEnabled = true;

                }catch (JSONException ex){
                    callbackContext.error("Could not parse the provided parameters to this action. Error: "+ex.getMessage());
                }
            }
        } else if (ACTION_STOP_UPDATE.equalsIgnoreCase(action)) {
            isUpdateEnabled = false;
            activity.stopService(updateServiceIntent);
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
    }
}
