
const basepath = process.cwd();

const _ = require('lodash')
    , cors = require('cors')
    , dotenv = require('dotenv').config()
    , configyml = require('@stefcud/configyml')
    , {name, version} = require(`${basepath}/package.json`)
    , serviceName = `service ${name} v${version}`;

const configDefault = configyml({basepath: __dirname});

const config = configyml({basepath});

config.endpoints = _.mapValues(config.endpoints, conf => {
    return _.defaults(conf, config.endpoints.default, configDefault.endpoints.default);
});
delete config.endpoints.default;

config.cors = _.defaults(config.cors, configDefault.cors);


//TODO return app
//if (config.envId == 'dev') {
//    app.set('json spaces', 2);
//}
module.exports = {

    config,
    configDefault,
    serviceName,
    version,
    cors: cors(config.cors),

/*    onInit: app => {
        console.log(`Starting ${serviceName}...`);
        console.log("Config:\n", config);
    },*/

    goListen: app => {
        app.listen(config.listen_port, onListen(app) );
        const {name, version} = require('./package.json');
        const serviceName = `service ${name} v${version}`;
        return function () {
            console.log( app._router.stack.filter(r => r.route).map(r => `${Object.keys(r.route.methods)[0]} ${r.route.path}`) );
            console.log(`${serviceName} listening at http://localhost:${this.address().port}`);
        }
    }
};