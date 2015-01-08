var app = {
    // Application Constructor
    initialize: function() {
        this.bindEvents();
    },
    // Bind Event Listeners
    //
    // Bind any events that are required on startup. Common events are:
    // 'load', 'deviceready', 'offline', and 'online'.
    bindEvents: function() {
        document.addEventListener('deviceready', this.onDeviceReady, false);
    },
    // deviceready Event Handler
    //
    // The scope of 'this' is the event. In order to call the 'receivedEvent'
    // function, we must explicity call 'app.receivedEvent(...);'
    onDeviceReady: function() {
        if (immobilize) {
            document.getElementById('startUpdating').addEventListener('click', function () {
                alert('Starting updates every 50 meters');
                immobilize.update(50, 'http://110.74.221.189:8181/cathis.ws/api/drivers/20141230192831810/locations', 'MjAxNDEyMzAxOTMwMTcwNzdnTUh5c2F5em1uV2dSdFV0UXBYc3haallHbmpwY3RDdXhnTUx6ZUhkaHR2eW1WSXZ4QzE0MjA0NTg3OTM4MTM=');
            }, false);
            document.getElementById('stopUpdating').addEventListener('click', function () {
                alert('Stoping updates');
                immobilize.stopUpdate();
            }, false);
            document.getElementById('startWatching').addEventListener('click', function () {
                alert('Watching for Immobilize within 200 meters over 1 minute');
                immobilize.watchImmobilise(200, 60,'http://110.74.221.189:8181/cathis.ws/api/drivers/20141230192831810/immobilize', 'MjAxNDEyMzAxOTMwMTcwNzdnTUh5c2F5em1uV2dSdFV0UXBYc3haallHbmpwY3RDdXhnTUx6ZUhkaHR2eW1WSXZ4QzE0MjA0NTg3OTM4MTM=');
            }, false);
            document.getElementById('stopWatching').addEventListener('click', function () {
                alert('Stoping updates');
                immobilize.stopWatch();
            }, false);
        }
        app.receivedEvent('deviceready');
    },
    // Update DOM on a Received Event
    receivedEvent: function(){
        console.log('Received Event: ' + arguments);
    }
};
app.initialize();