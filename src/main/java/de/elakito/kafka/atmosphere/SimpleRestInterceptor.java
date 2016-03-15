/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package de.elakito.kafka.atmosphere;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * Atmosphere interceptor to enable a simple rest-websocket binding protocol.
 * This protocol is a simplified version of SwaggerSocket. 
 * https://github.com/swagger-api/swaggersocket
 * 
 * This interceptor can currently handle both Websocket and SSE protocols.
 * 
 * @author elakito
 *
 */
public class SimpleRestInterceptor extends AtmosphereInterceptorAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(SimpleRestInterceptor.class);
  private final static String REQUEST_DISPATCHED = "request.dispatched";
  private final static String REQUEST_ID = "request.id";
  private final static byte[] RESPONSE_TEMPLATE_HEAD = "{\"id\": \"".getBytes();
  private final static byte[] RESPONSE_TEMPLATE_BELLY = "\", \"data\": ".getBytes();
  private final static byte[] RESPONSE_TEMPLATE_TAIL = "}".getBytes();
  private final static String HEARTBEAT_BROADCASTER_NAME = "/kafka-rest.heartbeat";
  private final static String HEARTBEAT_SCHEDULED = "heatbeat.scheduled";
  private final static String HEARTBEAT_TEMPLATE = "{\"heartbeat\": \"%s\", \"time\": %d}";
  private final static long DEFAULT_HEARTBEAT_INTERVAL = 60;

  private Map<String, AtmosphereResponse> suspendedResponses = new HashMap<String, AtmosphereResponse>();
  
  private Broadcaster heartbeat;
  // REVISIST more appropriate to store this status in servetContext to avoid scheduling redundant heartbeats?
  private boolean heartbeatScheduled;
  private final AsyncIOInterceptor interceptor = new Interceptor();
  public SimpleRestInterceptor() {
  }

  @Override
  public void configure(AtmosphereConfig config) {
    super.configure(config);
    //TODO make the heartbeat configurable
    heartbeat = config.getBroadcasterFactory().lookup(DefaultBroadcaster.class, HEARTBEAT_BROADCASTER_NAME);
    if (heartbeat == null) {
      heartbeat = config.getBroadcasterFactory().get(DefaultBroadcaster.class, HEARTBEAT_BROADCASTER_NAME);
    }
  }

  @Override
  public Action inspect(final AtmosphereResource r) {
    if (AtmosphereResource.TRANSPORT.WEBSOCKET != r.transport() 
        && AtmosphereResource.TRANSPORT.SSE != r.transport()
        && AtmosphereResource.TRANSPORT.POLLING != r.transport()) {
      //TODO swtich the logging to debug
      LOG.info("Skipping for non websocket request");
      return Action.CONTINUE;
    }
    if (AtmosphereResource.TRANSPORT.POLLING == r.transport()) {
      final String saruuid = (String)r.getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
      final AtmosphereResponse suspendedResponse = suspendedResponses.get(saruuid);
      LOG.info("Attaching a proxy writer to suspended response");
      r.getResponse().asyncIOWriter(new AtmosphereInterceptorWriter() {
          @Override
          public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
            suspendedResponse.write(data);
            suspendedResponse.flushBuffer();
            return this;
          }

          @Override
          public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
            suspendedResponse.write(data);
            suspendedResponse.flushBuffer();
            return this;
          }

          @Override
          public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
            suspendedResponse.write(data, offset, length);
            suspendedResponse.flushBuffer();
            return this;
          }
      });
      // REVISIT we need to keep this response's asyncwriter alive so that data can be written to the 
      //   suspended response, but investigate if there is a better alternative. 
      r.getResponse().destroyable(false);
      return Action.CONTINUE;
    }

    r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
      @Override
      public void onSuspend(AtmosphereResourceEvent event) {
        final String srid = (String)event.getResource().getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
        LOG.info("Registrering suspended resource: {}", srid);
        suspendedResponses.put(srid, event.getResource().getResponse());

        AsyncIOWriter writer = event.getResource().getResponse().getAsyncIOWriter();
        if (writer == null) {
            writer = new AtmosphereInterceptorWriter();
            r.getResponse().asyncIOWriter(writer);
        }
        if (writer instanceof AtmosphereInterceptorWriter) {
            ((AtmosphereInterceptorWriter)writer).interceptor(interceptor);
        }
      }

      @Override
      public void onDisconnect(AtmosphereResourceEvent event) {
        super.onDisconnect(event);
        final String srid = (String)event.getResource().getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
        LOG.info("Unregistrering suspended resource: {}", srid);
        suspendedResponses.remove(srid);
      }

    });

    AtmosphereRequest request = r.getRequest();
    if (request.getAttribute(REQUEST_DISPATCHED) == null) {
      try {
        // read the message entity and dispatch a service call
        String body = IOUtils.readEntirelyAsString(r).toString();
        LOG.info("Request message: '{}'", body);
        if (body.length() == 0) {
          //TODO we might want to move this heartbeat scheduling after the handshake phase (if that is added)
          if ((AtmosphereResource.TRANSPORT.WEBSOCKET == r.transport() ||
               AtmosphereResource.TRANSPORT.SSE == r.transport())
              && request.getAttribute(HEARTBEAT_SCHEDULED) == null) {
            r.suspend();
            scheduleHeartbeat(r);
            request.setAttribute(HEARTBEAT_SCHEDULED, "true");
            return Action.SUSPEND;
          }
          return Action.CANCELLED;
        }

        //REVISIT find a more efficient way to read and extract the message data
        JSONEnvelopeReader jer = new JSONEnvelopeReader(new StringReader(body));

        AtmosphereRequest ar = createAtmosphereRequest(request, jer);
        AtmosphereResponse response = r.getResponse();
        ar.localAttributes().put(REQUEST_DISPATCHED, "true");

        request.removeAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE);
        response.request(ar);

        attachWriter(r);

        Action action = r.getAtmosphereConfig().framework().doCometSupport(ar, response);
        if (action.type() == Action.TYPE.SUSPEND) {
          ar.destroyable(false);
          response.destroyable(false);
        }
        return Action.CANCELLED;
      } catch (JsonParseException | JsonMappingException e) {
        LOG.error("Invalid message format", e);
      } catch (IOException | ServletException e) {
        LOG.error("Failed to process", e);
      }
    }
        
    return Action.CONTINUE;
  }

  private void scheduleHeartbeat(AtmosphereResource r) {
    //REVISIT make the schedule configurable
    heartbeat.addAtmosphereResource(r);
    if (!heartbeatScheduled) {
      String identity = UUID.randomUUID().toString();
      heartbeat.scheduleFixedBroadcast(String.format(HEARTBEAT_TEMPLATE, identity, System.currentTimeMillis()),
          DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
      heartbeatScheduled = true;
    }
  }

  private AtmosphereRequest createAtmosphereRequest(AtmosphereRequest request, JSONEnvelopeReader jer) {
    AtmosphereRequest.Builder b = new AtmosphereRequestImpl.Builder();
    final String id = jer.getHeader("id");
    if (id != null) {
      request.localAttributes().put(REQUEST_ID, id);
    }
    final String method = jer.getHeader("method"); 
    String path = jer.getHeader("path");
    final String type = jer.getHeader("type");
    final String accept = jer.getHeader("accept");
    b.method(method != null ? method : "GET").pathInfo(path != null ? path: "/");
    if (accept != null || type != null) {
        Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (accept != null) {
          headers.put("Accept", accept);
        }
        if (type != null) {
          b.contentType(type);
        }
        b.headers(headers);
    }
    final int qpos = path.indexOf('?');
    if (qpos > 0) {
      b.queryString(path.substring(qpos + 1));
      path = path.substring(0, qpos);
    }
    final Reader data = jer.getReader();
    if (data != null) {
      b.reader(data);
    }
    String requestURL = request.getRequestURL() + path.substring(request.getRequestURI().length());
    b.requestURI(path).requestURL(requestURL).request(request);

    return b.build();
  }

  private byte[] createResponse(AtmosphereResponse response, byte[] payload) {
    //TODO swtich the logging to debug
    LOG.info("createResponse: payload=" + new String(payload));
    AtmosphereRequest request = response.request();
    String id = (String)request.getAttribute(REQUEST_ID);
    if (id == null) {
      // control response such as heartbeat or plain responses
      return payload;
    }
    //TODO find a nicer way to build the response entity
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    if (id != null) {
      try {
        baos.write(RESPONSE_TEMPLATE_HEAD);
        baos.write(id.getBytes());
        baos.write(RESPONSE_TEMPLATE_BELLY);
        baos.write(payload);
        baos.write(RESPONSE_TEMPLATE_TAIL);
      } catch (IOException e) {
        //ignore as it can't happen
      }
    }
    return baos.toByteArray();
  }
  private void attachWriter(final AtmosphereResource r) {
    AtmosphereResponse res = r.getResponse();
    AsyncIOWriter writer = res.getAsyncIOWriter();

    if (writer instanceof AtmosphereInterceptorWriter) {
      //REVIST need a better way to add a custom filter at the first entry and not at the last as
      // e.g. interceptor(AsyncIOInterceptor interceptor, int position)
      Deque<AsyncIOInterceptor> filters = AtmosphereInterceptorWriter.class.cast(writer).filters();
      if (!filters.contains(interceptor)) {
        filters.addFirst(interceptor);
      }
    }
  }

  private final class Interceptor extends AsyncIOInterceptorAdapter {
    @Override
    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
      return createResponse(response, responseDraft);
    }
  }    
}
