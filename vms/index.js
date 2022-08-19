const express = require('express');
const https = require('https');
const _ = require('lodash');
const cors = require('cors');

const pkg = require('./package.json')
    , version = pkg.version
    , serviceName = `service ${pkg.name} v${version}`
    , dotenv = require('dotenv').config()
    , config = require('@stefcud/configyml');

//normalize endpoints default
config.endpoints = _.mapValues(config.endpoints, conf => {
    return _.defaults(conf, config.endpoints.default);
});
delete config.endpoints.default;

var corsOptions = {
  origin: '*',
  optionsSuccessStatus: 200 // some legacy browsers (IE11, various SmartTVs) choke on 204
}

var app = express();

var lastUpdate = Math.trunc((new Date()).getTime() / 1000 ),
    stationsReceived,
    sensorsReceived;

console.log(`Starting ${serviceName}...`);

console.log("Config:\n", config);

if(!config.endpoints || _.isEmpty(config.endpoints)) {
    console.error('Config endpoints not defined!');
    return;
}

//TODO up to here MOVE in LIB module

function getData(){
    lastUpdate = Math.trunc((new Date()).getTime() / 1000 );
    getStations();
}
getData();
setInterval(getData, config.polling_interval * 1000);

function getStations(){
    const req = https.request(config.endpoints.stations, res => {
            //console.log(`STATIONS: statusCode: ${res.statusCode}`)
            var str = "";
            res.on('data', function (chunk) {
                str += chunk;
            });

            res.on('end', function () {
                let tmp = JSON.parse(str);
                var stations = tmp.data;
                stationsReceived = stations;
            });
        })

    req.on('error', error => {
        console.error(error)
    })

    req.end()
}

app.get('/vms/stations.json', cors(corsOptions),  function (req, res) {
    var parkingStations = [];
    if(stationsReceived){
        for(var i = 0; i < stationsReceived.length; i++){
            var station = stationsReceived[i];
            if(station.sactive && station.scoordinate && station.smetadata){
                parkingStations.push({
                    station_id: station.scode,
                    name: station.sname,
                    lat: station.scoordinate.y,
                    lon: station.scoordinate.x,
                    address: station.smetadata.mainaddress,
                    city: station.smetadata.municipality,
                    capacity: station.smetadata.capacity || 0,
                    free: station.mvalue || 0
                })
            }
        }
    }
    res.json({
        last_updated: lastUpdate,
        ttl: 0,
        version,
        data: {
            stations: parkingStations
       }
    });
});

app.get(['/','/vms'], async (req, res) => {
  res.send({
    status: 'OK',
    version
  });
});

app.listen(config.listen_port, function () {
    console.log( app._router.stack.filter(r => r.route).map(r => `${Object.keys(r.route.methods)[0]} ${r.route.path}`) );
    console.log(`${serviceName} listening at http://localhost:${config.listen_port}`);
});