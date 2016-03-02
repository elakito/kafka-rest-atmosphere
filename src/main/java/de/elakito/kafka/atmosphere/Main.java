package de.elakito.kafka.atmosphere;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.confluent.kafkarest.KafkaRestConfig;

public class Main {
  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    KafkaRestConfig config = new KafkaRestConfig((args.length > 0 ? args[0] : null));
    AtmosphereApplication app = new AtmosphereApplication(config);
    app.start();
    LOG.info("Server started, listening for requests...");
    app.join();
  }
}
