Node.js kafka-rest-atmospher Demo client using atmosphere.js
========================================================================

Running the demo
---------------------------------------

Start the server side of kafka-rest-atmosphere.

After the server has been started, start this client by executing the following shell commands.

```bash
% npm install atmosphere.js
% node client.js
```

This will connect to the kafka-rest-atmosphere server running on the local host. To connect
to a remote server, specify the remote url <remote_url>.

```bash
% node client.js <remote_url>
```

This client program supports websocket and sse and allows
you to choose your preferred protocol.

