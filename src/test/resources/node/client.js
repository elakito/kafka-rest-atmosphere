/**
 * client.js
 * 
 * A client demo program to produce or consume messages over kakfa-rest-atmosphere
 * proxy using its supported transport protocols such as websocket and SSE.
 *
 */

"use strict";

/////////////// configurable properties
// set host URL of kafka-rest-atmosphere
var HOST_URL = 'http://localhost:8082/';
// set trace to true to increase trace output
var TRACE = true;
///////

if (process.argv.length >= 3) {
    HOST_URL = process.argv[2]
    if (!HOST_URL.endsWith('/')) {
        HOST_URL += '/'
    }
}
console.log("Host URL: " + HOST_URL)

var reader = require('readline');
var prompt = reader.createInterface(process.stdin, process.stdout);

var atmosphere = require('atmosphere.js');

var request = { url: HOST_URL,
                transport : 'websocket',
                trackMessageLength: false,
                reconnectInterval : 5000};
var isopen = false;

// request/respons index
var count = 0;

// keep track of the subscriptions {instance : {'id': id, 'mode': mode, 'group': group, 'topic': topic}}
var subscriptions = {};
// keep track of the mode for each id {id : mode}
var decodables = {};

// available transport protocols
const TRANSPORT_NAMES = ["websocket", "sse"];

// producer's consle commands
const COMMAND_LIST = 
    [["cre"   ,     "Create a consumer instance", "cre mode group instance"],
     ["del",        "Delete a consumer instance", "del group instance"],
     ["help",       "Displays this help or help about a command", "help [command]"],
     ["info",       "Display detailed information about a topic", "info topic"],
     ["ls",         "List subscriptions", "ls"],
     ["list",       "List topics", "list"],
     ["pub",        "Publish a message to a topic in binary or json mode", "pub mode topic message"],
     ["sub",        "Subscribe to a topic for a consumer instance", "sub mode group instance topic"],
     ["unsub",      "Unsubscribe from a topic for a consumer instance", "unsub group instance topic"],
     ["quit",       "Quit the application", "quit"]];


/////////////// utilities

function getNextId() {
    return (count++).toString();
}

function selectOption(c, opts) {
    var i = c.length == 0 ? 0 : parseInt(c);
    if (!(i >= 0 && i < opts.length)) {
        console.log('Invalid selection: ' + c + '; Using ' + opts[0]);
        i = 0;
    }
    return opts[i];
}

function removeQuotes(msg) {
    if (msg.length >= 2) {
        var cb = msg.charAt(0);
        var ce = msg.charAt(msg.length - 1);
        if ((cb == '"' && ce == '"') || (cb == "'" && ce == "'")) {
            return msg.substring(1, msg.length - 1);
        }
    }
    return msg;
}

function encodeString(v, inenc, outenc) {
    return new Buffer(v, inenc).toString(outenc);
}

function getArgs(name, msg, num) {
    var sp = name.length;
    if (msg.length > name.length && msg.charAt(name.length) != ' ') {
        // remove the command suffix
        sp = msg.indexOf(' ', name.length);
        if (sp < 0) {
            sp = msg.length;
        }
    }
    var v = msg.substring(sp).trim();
    if (v.length > 0) {
        if (num == 0) {
            // return as a single value
            return v;
        } else {
            // return as an array of num + 1 elements containing the first num arguments and the rest
            var oldp = 0;
            var params = [];
            while (num > 0) {
                var p = v.indexOf(' ', oldp);
                if (p < 0) {
                    break;
                }
                var param = v.substring(oldp, p);
                params.push(param);
                num--;
                v = v.substring(p + 1).trim();
            }
            if (num > 1) {
                throw "Invalid arguments for " + name;
            }
            params.push(v);
            return params;
        }
    }
}

function extractValues(msg, mode) {
    var res = [];
    for (var i = 0; i < msg.length; i++) {
        res.push(extractValue(msg[i], mode));
    }
    return res;
}

function extractValue(msg, mode) {
    if (!msg.value) {
        return "";
    }
    if ("binary" == mode) {
        return encodeString(msg.value, 'base64', 'utf-8');
    } else {
        return msg.value;
    }
}

function errorUsage(v, msg) {
    console.log("Error: Missing arguments for " + v);
    for (var i = 0; i < COMMAND_LIST.length; i++) { 
        var c = COMMAND_LIST[i][0];
        if (v == c) {
            console.log("Usage: " + COMMAND_LIST[i][2]);
        }
    }
}

function queryTransport() {
    console.log("Select transport ...");
    for (var i = 0; i < TRANSPORT_NAMES.length; i++) { 
        console.log(i + ": " + TRANSPORT_NAMES[i]);
    }
    prompt.setPrompt("select: ", 6);
    prompt.prompt();
}

/////////////// commands

function doHelp(v) {
    if (!v) {
        console.log('Available commands');
        for (var i = 0; i < COMMAND_LIST.length; i++) { 
            var c = COMMAND_LIST[i][0];
            console.log(c + "                    ".substring(0, 20 - c.length) + COMMAND_LIST[i][1]);
        }
    } else {
        var found = false;
        for (var i = 0; i < COMMAND_LIST.length; i++) { 
            var c = COMMAND_LIST[i][0];
            if (v == c) {
                console.log(COMMAND_LIST[i][1]);
                console.log("Usage: " + COMMAND_LIST[i][2]);
                found = true;
            }
        }
        if (!found) {
            throw "Uknown command: " + v;
        }
    }
}

function doList() {
    var req;
    if (transport == 'websocket') {
        req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "GET", "path": "/topics"});
    } else if (transport == 'sse') {
        req = {"method": "GET", "url": HOST_URL + "topics", "data":undefined}
    }
    if (TRACE) {
        console.log("TRACE: sending ", req);
    }
    subSocket.push(req);
}

function doLs() {
    console.log("Subscribed:", subscriptions);
}

function doInfo(v) {
    if (!v) {
        errorUsage("info");
        return;
    }
    var req;
    if (transport == 'websocket') {
        req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "GET", "path": "/topics/" + v[0]});
    } else if (transport == 'sse') {
        req = {"method": "GET", "url": HOST_URL + "topics/" + v[0], "data":undefined}
    }
    
    if (TRACE) {
        console.log("TRACE: sending ", req);
    }
    subSocket.push(req);
}

function doPublish(v) {
    if (!v) {
        errorUsage("pub");
        return;
    }
    var req;
    if (v[0].indexOf("b") == 0) {
        if (transport == 'websocket') {
            req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "POST", "path": "/topics/" + v[1], "type": "application/vnd.kafka.binary.v1+json", "data": {"records": [{"value": encodeString(v[2], 'utf-8', 'base64')}]}});
        } else if (transport == 'sse') {
            req = {"method": "POST", "url": HOST_URL + "topics/" + v[1], "contentType": "application/vnd.kafka.binary.v1+json", "data":atmosphere.util.stringifyJSON({"records": [{"value": encodeString(v[2], 'utf-8', 'base64')}]})};
        }
        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
        subSocket.push(req);
    } else if (v[0].indexOf("j") == 0) {
        if (transport == 'websocket') {
            req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "POST", "path": "/topics/" + v[1], "type": "application/vnd.kafka.json.v1+json", "data": {"records": [{"value": JSON.parse(removeQuotes(v[2]))}]}});
        } else if (transport == 'sse') {
            req = {"method": "POST", "url": HOST_URL + "topics/" + v[1], "contentType": "application/vnd.kafka.json.v1+json", "data":atmosphere.util.stringifyJSON({"records": [{"value": JSON.parse(removeQuotes(v[2]))}]})};       
        }
        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
        subSocket.push(req);
    } else {
        throw "Uknown mode: " + v[0];
    }
}

function doCreate(v) {
    if (!v) {
        errorUsage("cre");
        return;
    }
    if (v[0].indexOf("b") == 0) {
        var req;
        if (transport == 'websocket') {
            req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "POST", "path": "/consumers/" + v[1], "type": "application/vnd.kafka.binary.v1+json", "data": {"id": v[2], "format": "binary", "auto.offset.reset": "smallest"}});
        } else if (transport == 'sse') {
            req = {"method": "POST", "url": HOST_URL + "consumers/" + v[1], "contentType": "application/vnd.kafka.binary.v1+json", "data":atmosphere.util.stringifyJSON({"id": v[2], "format": "binary", "auto.offset.reset": "smallest"})};
        }

        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
        subSocket.push(req);
    } else if (v[0].indexOf("j") == 0) {
        if (transport == 'websocket') {
            req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "POST", "path": "/consumers/" + v[1], "type": "application/vnd.kafka.json.v1+json", "data": {"id": v[2], "format": "json", "auto.offset.reset": "smallest"}});
        } else if (transport == 'sse') {
            req = {"method": "POST", "url": HOST_URL + "consumers/" + v[1], "contentType": "application/vnd.kafka.json.v1+json", "data":atmosphere.util.stringifyJSON({"id": v[2], "format": "json", "auto.offset.reset": "smallest"})};
        }

        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
       subSocket.push(req);
    } else {
        throw "Uknown mode: " + v[0];
    }
}

function doDelete(v) {
    if (!v) {
        errorUsage("del");
        return;
    }
    var req;
    if (transport == 'websocket') {
        req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "DELETE", "path": "/consumers/" + v[0] + "/instances/" + v[1]});
    } else if (transport == 'sse') {
        req = {"method": "DELETE", "url": HOST_URL + "consumers/" + v[0] + "/instances/" + v[1], "data":undefined}
    }

    if (TRACE) {
        console.log("TRACE: sending ", req);
    }
    subSocket.push(req);
}

function doSubscribe(v) {
    if (!v) {
        errorUsage("sub");
        return;
    }
    var req;
    if (v[0].indexOf("b") == 0) {
        var tid = getNextId();
        if (transport == 'websocket') {
            req = atmosphere.util.stringifyJSON({ "id": tid, "method": "GET", "path": "/ws/consumers/" + v[1] + "/instances/" + v[2] + "/topics/" + v[3], "accept": "application/vnd.kafka.binary.v1+json"});
        } else if (transport == 'sse') {
            req = {"method": "GET", "url": HOST_URL + "ws/consumers/" + v[1] + "/instances/" + v[2] + "/topics/" + v[3], "headers": {"accept": "application/vnd.kafka.binary.v1+json"}, "data":undefined};
        }
        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
        subSocket.push(req);
        subscriptions[v[2]] = {"id": tid, "mode": "binary", "group": v[1], "topic": v[3]};
        decodables[tid] = "binary";
    } else if (v[0].indexOf("j") == 0) {
        var tid = getNextId();
        if (transport == 'websocket') {
            req  = atmosphere.util.stringifyJSON({ "id": tid, "method": "GET", "path": "/ws/consumers/" + v[1] + "/instances/" + v[2] + "/topics/" + v[3], "accept": "application/vnd.kafka.json.v1+json"});
        } else if (transport == 'sse') {
            req = {"method": "GET", "url": HOST_URL + "ws/consumers/" + v[1] + "/instances/" + v[2] + "/topics/" + v[3], "headers": {"accept": "application/vnd.kafka.json.v1+json"}, "data":undefined};
        }
        if (TRACE) {
            console.log("TRACE: sending ", req);
        }
        subSocket.push(req);
        subscriptions[v[2]] = {"id": tid, "mode": "json", "group": v[1], "topic": v[3]};
        decodables[tid] = "json";
    } else {
        throw "Uknown mode: " + v[0];
    }
}

function doUnsubscribe(v) {
    if (!v) {
        errorUsage("unsub");
        return;
    }
    var req;
    if (transport == 'websocket') {
        req = atmosphere.util.stringifyJSON({ "id": getNextId(), "method": "DELETE", "path": "/ws/consumers/" + v[0] + "/instances/" + v[1] + "/topics/" + v[2]});
    } else if (transport == 'sse') {
        req = {"method": "DELETE", "url": HOST_URL + "ws/consumers/" + v[0] + "/instances/" + v[1] + "/topics/" + v[2], "data":undefined}
    }
    if (TRACE) {
        console.log("TRACE: sending ", req);
    }
    subSocket.push(req);
    delete decodables[subscriptions[v[1]].id];
    delete subscriptions[v[1]];
}

function doQuit() {
    subSocket.close();
    process.exit(0);
}


request.onOpen = function(response) {
    isopen = true;
    console.log('Connected using ' + response.transport);
    prompt.setPrompt("> ", 2);
    prompt.prompt();
};

request.onReopen = function(response) {
    isopen = true;
    console.log('Reopened using ' + response.transport);
    prompt.setPrompt("> ", 2);
    prompt.prompt();
}

request.onReconnect = function(response) {
    console.log("Reconnecting ...");
}

request.onMessage = function (response) {
    var message = response.responseBody;
    var jpart;
    var data;
    var json;
    //FIXME use a better logic to determine the mode
    if (message.indexOf("detached") > 0) {
        var d = message.indexOf('\n');
        if (d > 0) {
            data = message.substring(d + 1);
            jpart = message.substring(0, d);
        }
    }
    if (!jpart) {
        jpart = message;
    }
    try {
        json = JSON.parse(jpart);
    } catch (e) {
        console.log('Invalid response: ', message);
        return;
    }
    if (json.heartbeat) {
        // ignore
    } else {
        if (TRACE) {
            console.log("TRACE: received " + message);
            prompt.setPrompt("> ", 2);
            prompt.prompt();
        }

        if (json.error_code) {
            console.log(atmosphere.util.stringifyJSON(json));
        } else if (json.id) {
            var decodable = decodables[json.id];
            if (decodable && json.data) {
                if (json.data instanceof Array) {
                    console.log("res" + json.id + ":", extractValues(json.data, decodable))
                } else if (json.data instanceof Object) {
                    console.log("res" + json.id + ":", extractValue(json.data, decodable));
                } else {
                    console.log("res" + json.id + ":", atmosphere.util.stringifyJSON(json.data));
                }
            } else if (data) {
                console.log("res" + json.id + ":", data);
            } else {
                console.log("res" + json.id + ":", atmosphere.util.stringifyJSON(json.data));
            }
        } else {
            // no id supplied in the response, so just write the plain result
            console.log("res*" + ":", atmosphere.util.stringifyJSON(json))
        }
    }
    prompt.setPrompt("> ", 2);
    prompt.prompt();
};

request.onClose = function(response) {
    console.log("Closed");
    isopen = false;
}

request.onError = function(response) {
    console.log("Sorry, something went wrong: " + response.responseBody);
};

var transport = null;
var subSocket = null;

queryTransport();

prompt.
on('line', function(line) {
    try {
        var msg = line.trim();
        if (transport == null) {
            transport = selectOption(msg, TRANSPORT_NAMES);
            request.transport = transport;

            subSocket = atmosphere.subscribe(request);
            console.log("Connecting using " + request.transport + " ...");
            setTimeout(function() {
                if (!isopen) {
                    console.log("Unable to open a connection. Terminated.");
                    process.exit(0);
                }
            }, 3000);
        } else if (msg.length == 0) {
            doHelp();
        } else if (msg.indexOf("cre") == 0) {
            doCreate(getArgs("cre", msg, 3));
        } else if (msg.indexOf("del") == 0) {
            doDelete(getArgs("del", msg, 2));
        } else if (msg.indexOf("help") == 0) {
            doHelp(getArgs("help", msg, 0));
        } else if (msg.indexOf("help") == 0) {
            doHelp(getArgs("help", msg, 0));
        } else if (msg.indexOf("info") == 0) {
            doInfo(getArgs("info", msg, 1));
        } else if (msg.indexOf("list") == 0) {
            doList();
        } else if (msg.indexOf("ls") == 0) {
            doLs();
        } else if (msg.indexOf("pub") == 0) {
            doPublish(getArgs("pub", msg, 2));
        } else if (msg.indexOf("sub") == 0) {
            doSubscribe(getArgs("sub", msg, 4));
        } else if (msg.indexOf("unsub") == 0) {
            doUnsubscribe(getArgs("unsub", msg, 3));
        } else if (msg.indexOf("quit") == 0) {
            doQuit();
        } else {
            throw "Uknown command: " + msg;
        }
    } catch (err) {
        console.log("Error: " + err);
    }
    prompt.setPrompt("> ", 2);
    prompt.prompt();
}).

on('close', function() {
    doQuit();
});
