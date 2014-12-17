cordova.define("au.com.cathis.plugin.message.immobilize.Immobilize", function(require, exports, module) { var exec = require("cordova/exec");
module.exports = {

    /**
     * This method is used to update the latest location to server.
     * @param {Integer} movingAccuracy – max distance range allowance in meters for GPS accuracy
     * @param {String} apiUrl – API URL to access when movingAccuracy condition is met
     * @param {String} accessToken – key to authenticate API service
     * @param {Function} success (optional) – a callback function to be called if the operation was successful
     * @param {Function} failure (optional) – a callback function to be called if the operation failed
     */
    update: function(movingAccuracy, apiUrl, accessToken, success, failure) {
        var successCallback = function(){
            //Needed to provide permissions
            window.navigator.geolocation.getCurrentPosition(function(location) {
                console.log('Location from Cordova');
            });
            if(success)
                 success.call(this,arguments);
        }
        exec(successCallback || function() {},
             failure || function() {},
             'Immobilize',
             'update',
             [movingAccuracy, apiUrl, accessToken]);
    },

    /**
     * This method is used to stop updates of the location to server.
     * @param {Function} success (optional) – a callback function to be called if the operation was successful
     * @param {Function} failure (optional) – a callback function to be called if the operation failed
     */
    stopUpdate: function(success, failure) {
        exec(success || function() {},
            failure || function() {},
            'Immobilize',
            'stopUpdate',
            []);
    },

    /**
     * This method is used to check for immobilise and update status to server.
     * @param {Integer} movingAccuracy – max distance range allowance in meters for GPS accuracy
     * @param {Integer} immobiliseDuration – time allowance for immobilize in seconds
     * @param {String} apiUrl – API URL to access when movingAccuracy and immobiliseDuration is met
     * @param {String} accessToken – key to authenticate API service
     * @param {Function} success (optional) – a callback function to be called if the operation was successful
     * @param {Function} failure (optional) – a callback function to be called if the operation failed
     */
    watchImmobilise: function(movingAccuracy, immobiliseDuration, apiUrl, accessToken, success, failure) {
        var successCallback = function(){
            //Needed to provide permissions
            window.navigator.geolocation.getCurrentPosition(function(location) {
                console.log('Location from Cordova');
            });
            if(success)
                success.call(this,arguments);
        }
        exec(successCallback || function() {},
                failure || function() {},
            'Immobilize',
            'watchImmobilise',
            [movingAccuracy, immobiliseDuration, apiUrl, accessToken]);
    },

    /**
     * This method is used to stop watch for immobilise.
     * @param {Function} success (optional) – a callback function to be called if the operation was successful
     * @param {Function} failure (optional) – a callback function to be called if the operation failed
     */
    stopWatch: function(success, failure) {
        exec(success || function() {},
                failure || function() {},
            'Immobilize',
            'stopWatch',
            []);
    }
};

});
