const mqtt = require('async-mqtt');
const apiKey = require('./apikey');

exports.handler = async (event) => {
    console.log(event);
    let buff = new Buffer(event.body, 'base64');
    const parameters = new URLSearchParams(buff.toString('utf-8'));

    const whiteboardLocation = parameters.get('text');

    // send this on mqtt to the hivemq broker
    const dataToSend = {
        "temi_request": true,
        "location": whiteboardLocation
    };

    const options = {
        host: 'c51239432e1144d5864bbfca54347978.s1.eu.hivemq.cloud',
        port: 8883,
        protocol: 'mqtts',
        username: apiKey['hivemq_user'],
        password: apiKey['hivemq_password']
    };

    const client = await mqtt.connectAsync(options);
    try {
        await client.publish("temi-data", JSON.stringify(dataToSend));
        await client.end();
        console.log("Done");
        return {
            statusCode: 200,
            body: JSON.stringify("request sent to temi"),
        };
    } catch (e){
        console.log(e.stack);
        return {
            statusCode: 500,
            body: e.toString()
        };
    }
};
