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
        exec(success || function() {},
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
    }
};

});
