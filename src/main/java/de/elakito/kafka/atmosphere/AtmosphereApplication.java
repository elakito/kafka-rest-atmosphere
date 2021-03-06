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

import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;

import javax.servlet.DispatcherType;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.interceptor.CacheHeadersInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.interceptor.SimpleRestInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import io.confluent.kafkarest.KafkaRestApplication;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.rest.MetricsListener;
import io.confluent.rest.RestConfigException;
import io.confluent.rest.logging.Slf4jRequestLog;

/**
 * Standalone Atmosphere wrapper of KafkaApplication to use Atmosphere
 * 
 * @author elakito 
 */
public class AtmosphereApplication extends KafkaRestApplication {
  private final boolean disableAtmosphere = Boolean.getBoolean("de.elakito.kafka.atmosphere.disabled");

  public AtmosphereApplication() throws RestConfigException {
    this(new Properties());
  }

  public AtmosphereApplication(Properties props) throws RestConfigException {
    super(new KafkaRestConfig(props));
  }

  public AtmosphereApplication(KafkaRestConfig config) {
    super(config);
  }

  // The createServer code is replicated from io.confluent.rest.Application#createServer
  // with a minor change to inject AtmosphereServlet in front. If method createServletHolder
  // is added to Application#createServer, only that method needs to be overriden. 
  @Override
  public Server createServer() throws RestConfigException {
    ResourceConfig resourceConfig = new ResourceConfig();
    Map<String, String> metricTags = getMetricsTags();
    configureBaseApplication(resourceConfig, metricTags);
    setupResources(resourceConfig, getConfiguration());
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    ServletHolder servletHolder = createServletHolder(servletContainer);
    server = new Server() {
      protected void doStop() throws Exception {
        super.doStop();
        metrics.close();
        onShutdown();
        shutdownLatch.countDown();
      }
    };
    NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(server);
    connector.addNetworkTrafficListener(new MetricsListener(metrics, "jetty", metricTags));
    connector.setPort(getConfiguration().getInt("port"));
    server.setConnectors(new Connector[] {
      connector
    });
    ServletContextHandler context = new ServletContextHandler(1);
    context.setContextPath("/");
    context.addServlet(servletHolder, "/*");
    String allowedOrigins = getConfiguration().getString("access.control.allow.origin");
    if (allowedOrigins != null && !allowedOrigins.trim().isEmpty()) {
      FilterHolder filterHolder = new FilterHolder(new org.eclipse.jetty.servlets.CrossOriginFilter());
      filterHolder.setName("cross-origin");
      filterHolder.setInitParameter("allowedOrigins", allowedOrigins);
      context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
    }
    RequestLogHandler requestLogHandler = new RequestLogHandler();
    Slf4jRequestLog requestLog = new Slf4jRequestLog();
    requestLog.setLoggerName(config.getString("request.logger.name"));
    requestLog.setLogLatency(true);
    requestLogHandler.setRequestLog(requestLog);
    HandlerCollection handlers = new HandlerCollection();
    handlers.setHandlers(new Handler[] {
      context, new DefaultHandler(), requestLogHandler
    });
    StatisticsHandler statsHandler = new StatisticsHandler();
    statsHandler.setHandler(handlers);
    server.setHandler(statsHandler);
    int gracefulShutdownMs = getConfiguration().getInt("shutdown.graceful.ms");
    if (gracefulShutdownMs > 0) {
      server.setStopTimeout(gracefulShutdownMs);
    }
    server.setStopAtShutdown(true);
    return server;
  }

  private ServletHolder createServletHolder(ServletContainer servletContainer) {
    ServletHolder servletHolder;
    if (disableAtmosphere) {
      servletHolder = new ServletHolder(servletContainer);
    } else {
      ReflectorServletProcessor atmosphereHandler = new ReflectorServletProcessor(servletContainer);
      AtmosphereServlet proxyServlet = new AtmosphereServlet();
      AtmosphereFramework framework = proxyServlet.framework();
      framework.addAtmosphereHandler("/*", atmosphereHandler);
      // move the javascript protocol processor in front
      framework.interceptor(new CacheHeadersInterceptor()).interceptor(new HeartbeatInterceptor())
        .interceptor(new SSEAtmosphereInterceptor()).interceptor(new JavaScriptProtocol())
        .interceptor(new SimpleRestInterceptor());
           
      servletHolder = new ServletHolder(proxyServlet);
      servletHolder.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
      servletHolder.setInitParameter(ApplicationConfig.WEBSOCKET_PROTOCOL_EXECUTION, "true");
      servletHolder.setInitParameter(ApplicationConfig.ANALYTICS, "false");
      servletHolder.setInitParameter(ApplicationConfig.RESPONSE_COMPLETION_AWARE, "true");
      servletHolder.setInitParameter(ApplicationConfig.RESPONSE_COMPLETION_RESET, "true");

      if (config.getBoolean(KafkaRestAtmosphereConfig.ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_CONFIG)) {
        servletHolder.setInitParameter(KafkaRestAtmosphereConfig.ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_CONFIG, "true");
      }

      for (String key : KafkaRestAtmosphereConfig.ATMOSPHERE_PARAMETERS) {
        String v = config.getString(key);
        if (v.length() > 0) {
          servletHolder.setInitParameter(key, v);
        }
      }

    }
    return servletHolder;
  }
}
