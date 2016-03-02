package de.elakito.kafka.atmosphere;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SimpleRestInterceptor extends AtmosphereInterceptorAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(SimpleRestInterceptor.class);
  private final static String REQUEST_DISPATCHED = "request.dispatched";
  private final static String REQUEST_ID = "request.id";
  private final static byte[] RESPONSE_TEMPLATE_HEAD = "{\"id\": \"".getBytes();
  private final static byte[] RESPONSE_TEMPLATE_BELLY = "\", \"data\": ".getBytes();
  private final static byte[] RESPONSE_TEMPLATE_TAIL = "}".getBytes();
  private final static String HEARTBEAT_BROADCASTER_NAME = "/kafka-rest.heartbeat";
  private final static String HEARTBEAT_SCHEDULED = "heatbeat.scheduled";
  private final static String HEARTBEAT_TEMPLATE = "{\"heartbeat\": \"%x\", \"time\": %d}";
  private final static long DEFAULT_HEARTBEAT_INTERVAL = 60;

  private Broadcaster heartbeat;
  private final AsyncIOInterceptor interceptor = new Interceptor();
  private final ObjectMapper mapper;

  public SimpleRestInterceptor() {
    this.mapper = new ObjectMapper();
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
    if (AtmosphereResource.TRANSPORT.WEBSOCKET != r.transport()) {
      //TODO swtich the logging to debug
      LOG.info("Skipping for non websocket request");
      return Action.CONTINUE;
    }
    AtmosphereRequest request = r.getRequest();
    if (request.getAttribute(REQUEST_DISPATCHED) == null) {
      try {
        //TODO add LONG_POLLING handling

        // read the message entity and dispatch a service call
        String body = IOUtils.readEntirelyAsString(r).toString();
        LOG.info("Request message: '{}'", body);
        //TODO after defining the payload format, define the specific classes instead of Map
        if (body.length() == 0) {
          //TODO we might want to move this heartbeat scheduling after the handshake phase (if that is added)
          if (AtmosphereResource.TRANSPORT.WEBSOCKET == r.transport() 
              && request.getAttribute(HEARTBEAT_SCHEDULED) == null) {
            scheduleHeartbeat(r, System.identityHashCode(r));
            request.setAttribute(HEARTBEAT_SCHEDULED, "true");
          }

          return Action.CANCELLED;
        }
        //REVISIT find a more efficient way to read and extract the message data
        Map<?, ?> payload = mapper.readValue(body, HashMap.class);

        AtmosphereRequest ar = createAtmosphereRequest(request, payload);
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

  private void scheduleHeartbeat(AtmosphereResource r, int id) {
    //REVISIT make the schedule configurable
    heartbeat.addAtmosphereResource(r)
      .scheduleFixedBroadcast(String.format(HEARTBEAT_TEMPLATE, System.identityHashCode(r), System.currentTimeMillis()), 
                              DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
  }

  private AtmosphereRequest createAtmosphereRequest(AtmosphereRequest request, Map<?, ?> payload) {
    AtmosphereRequest.Builder b = new AtmosphereRequestImpl.Builder();
    final String id = (String)payload.get("id");
    if (id != null) {
      request.localAttributes().put(REQUEST_ID, id);
    }
    final String method = (String)payload.get("method"); 
    final String path = (String)payload.get("path");
    final String type = (String)payload.get("type");
    final String accept = (String)payload.get("accept");
    final Object data = payload.get("data");
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

    if (data != null) {
      b.body(getBytesValue(data));
    }
    String requestURL = request.getRequestURL() + path.substring(request.getRequestURI().length());
    b.requestURI(path).requestURL(requestURL).request(request);

    return b.build();
  }

  private byte[] createResponse(AtmosphereResponse response, byte[] payload) {
    AtmosphereRequest request = response.request();
    String id = (String)request.getAttribute(REQUEST_ID);
    if (id == null) {
      // control response such as heartbeat
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

  //REVISIT find a more efficient way to read and extract the message data
  private byte[] getBytesValue(Object o) {
    byte[] b = null;
    try {
      b = mapper.writeValueAsBytes(o);
    } catch (IOException e) {
      //ignore
    }
    return b;
  }
}
