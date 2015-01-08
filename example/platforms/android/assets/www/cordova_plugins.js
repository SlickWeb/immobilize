cordova.define('cordova/plugin_list', function(require, exports, module) {
module.exports = [
    {
        "file": "plugins/au.com.cathis.plugin.message.immobilize/www/immobilize.js",
        "id": "au.com.cathis.plugin.message.immobilize.Immobilize",
        "clobbers": [
            "immobilize"
        ]
    },
    {
        "file": "plugins/org.apache.cordova.dialogs/www/notification.js",
        "id": "org.apache.cordova.dialogs.notification",
        "merges": [
            "navigator.notification"
        ]
    },
    {
        "file": "plugins/org.apache.cordova.dialogs/www/android/notification.js",
        "id": "org.apache.cordova.dialogs.notification_android",
        "merges": [
            "navigator.notification"
        ]
    }
];
module.exports.metadata = 
// TOP OF METADATA
{
    "au.com.cathis.plugin.message.immobilize": "1.0.0",
    "org.apache.cordova.geolocation": "0.3.11",
    "org.apache.cordova.dialogs": "0.2.11"
}
// BOTTOM OF METADATA
});