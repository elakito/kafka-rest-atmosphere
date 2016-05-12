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

import java.util.Properties;

import io.confluent.common.config.ConfigDef.Importance;
import io.confluent.common.config.ConfigDef.Type;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.SystemTime;
import io.confluent.kafkarest.Time;
import io.confluent.rest.RestConfigException;

/**
 * 
 * @author elakito
 *
 */
public class KafkaRestAtmosphereConfig extends KafkaRestConfig {
  public static final String ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_CONFIG = "atmosphere.simple-rest.protocol.detached";
  private static final String ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_DOC =
      "The boolean flag used to enable the detache envelope mode of atmosphere-simple-rest";
  public static final String ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_DEFAULT = "true";

  static {
    config.define(ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_CONFIG, Type.BOOLEAN, ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_DEFAULT,
        Importance.MEDIUM, ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED_DOC);
  }
  public KafkaRestAtmosphereConfig() throws RestConfigException {
    this(new Properties());
  }

  public KafkaRestAtmosphereConfig(String propsFile) throws RestConfigException {
    this(getPropsFromFile(propsFile));
  }

  public KafkaRestAtmosphereConfig(Properties props) throws RestConfigException {
    this(props, new SystemTime());
  }

  public KafkaRestAtmosphereConfig(Properties props, Time time) throws RestConfigException {
    super(props, time);
  }

}
