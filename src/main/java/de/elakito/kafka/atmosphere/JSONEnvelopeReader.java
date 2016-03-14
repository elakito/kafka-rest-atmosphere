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

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * The JSON envelope used in the REST websocket binding is a JSON object
 * with zero or more headers, followed by an optional body. Each header
 * has a simple value and the body has name "data" and its value is 
 * an arbitrary JSON value.
 *   
 * @author elakito
 *
 */
public class JSONEnvelopeReader {
  private Reader reader;
  private Map<String, String> headers;
  private boolean datap;
  private int peek = -1;

  public JSONEnvelopeReader(Reader reader) throws IOException {
    this.reader = reader;
    this.headers = new HashMap<String, String>();

    prepare();
  }

  public String getHeader(String name) {
    return headers.get(name);
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public Reader getReader() {
    if (!datap) {
      return null;
    }

    return new Reader() {
      private int b;
      @Override
      public int read(char[] cbuf, int off, int len) throws IOException {
        int n = reader.read(cbuf, off, len);
        if (n > 0) {
          boolean escaping = false;
          char quot = 0;
          for (int i = off; i < n; i++) {
            char c = cbuf[i];
            if (c == '{' && !escaping) {
              b++;
            } else if (c == '}' && !escaping) {
              b--;
              if (b < 0) {
                  // past the logical eof
                  n--;
              }
            } else if ((c == '"' || c == '\'') && !escaping) {
              if (c == quot) {
                quot = 0;
              } else {
                quot = c;
              }
            } else if (c == '\\' && quot != 0 && !escaping) {
              escaping = true;
            } else if (escaping) {
              escaping = false;
            }
          }
        }
        return n;
      }

      @Override
      public void close() throws IOException {
        reader.close();
      }

      @Override
      public boolean ready() throws IOException {
        return reader.ready();
      }
    };
  }

  
  private void prepare() throws IOException {
    int c = next(true);
    if (c == '{') {
      for (;;) {
        String name = nextName();
        c = next(true);
        if (c == ':') {
          if ("data".equals(name)) {
            datap = true;
            break;
          } else {
            String value = nextValue();
            headers.put(name, value);
          }
        } else {
          throw new IOException("invalid value: missing name-separator ':'");
        }
        c = next(true);
        if (c != ',') {
          unread(c);
          break;
        }
      }
    } else {
      throw new IOException("invalid object: missing being-object '{'");
    }
  }

  private String nextName() throws IOException {
    int c = next(true);
    if (c == '"' || c == '\'') {
      return nextQuoted(c);
    }
    throw new IOException("invalid name: missing quote '\"'");
  }

  private String nextValue() throws IOException {
    int c = next(true);
    if (c == '"' || c == '\'') {
      // quoted string
      return nextQuoted(c);
    } else if (c == 't' || c == 'f' || ('0' <= c && c <= '9')) {
      // true, false, or number
      unread(c);
      return nextNonQuoted();
    }
    throw new IOException("invalid value: unquoted non literals");
  }

  private String nextQuoted(int quot) throws IOException {
    StringBuilder sb = new StringBuilder();
    boolean escaping = false;
    int c;
    while ((c = next(false)) != -1) {
      if (c == '\\' && !escaping) {
        escaping = true;
      } else if (c == quot && !escaping) {
        break;
      } else {
        sb.append((char) c);
        if (escaping) {
          escaping = false;
        }
      }
    }
    if (c != -1) {
      return sb.toString();
    }
    throw new IOException("invalid quoted string: missing quotation");
  }

  private String nextNonQuoted() throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = next(false)) != -1) {
      if (c == '}' || c == ',' || isWS(c)) {
        unread(c);
        break;
      } else {
        sb.append((char) c);
      }
    }
    if (c != -1) {
      return sb.toString();
    }
    throw new IOException("invalid value: non-terminated");
  }

  private int next(boolean skipws) throws IOException {
    int c;
    if (peek != -1) {
      c = peek;
      peek = -1;
    } else {
      while ((c = reader.read()) != -1 && skipws && isWS(c));
    }
    return c;
  }

  private void unread(int c) {
    peek = c;
  }

  private boolean isWS(int c) {
    return c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d;
  }
}
