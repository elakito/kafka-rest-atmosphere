package de.elakito.kafka.atmosphere;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import de.elakito.kafka.atmosphere.JSONEnvelopeReader;

public class JSONEnvelopeReaderTest extends Assert {
    @Test
    public void testParsingNoData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/xml\", \"accept\" : \"text/plain\" }";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/xml");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataApos() throws Exception {
        final String data = "{'id': \"123\", \"type\" : 'text/xml', 'accept' : 'text/plain' }";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/xml");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataAposMixedSpace() throws Exception {
        final String data = "{\n 'id':\"123\",\"type\" :'text/xml','accept'\n: 'text/plain'\r }";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/xml");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataNumber() throws Exception {
        final String data = "{'id': \"123\", \"size\" : 69124, 'ack' : true }";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);
        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", "69124");
        expectedHeaders.put("ack", "true");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataNumberMixedSpace() throws Exception {
        final String data = "{'id': \"123\", \"size\":69124, \r\n'ack' :true }";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);
        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", "69124");
        expectedHeaders.put("ack", "true");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingWithData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/xml\", \"data\": {\"records\": [{\"value\": \"S2Fma2E=\"}]}}";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/xml");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithMoreData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/xml\", "
                + "\"data\": {\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}}";
        Reader r = new StringReader(data);
        
        JSONEnvelopeReader jer = new JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/xml");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}");
    }

    private void verify(JSONEnvelopeReader jer, Map<String, String> expectedHeaders, String expectedBody) {
        Map<String, String> headers = jer.getHeaders(); 
        assertEquals(expectedHeaders.size(), headers.size());
        for (String key : expectedHeaders.keySet()) {
            assertEquals("value of key " + key + " differs", expectedHeaders.get(key), headers.get(key));
        }
        if (expectedBody == null) {
          assertNull(jer.getReader());
        } else {
          assertEquals(expectedBody, extractContent(jer.getReader()));
        }
    }

    private String extractContent(Reader reader) {
        char[] cbuf = new char[512];
        StringBuilder sb = new StringBuilder();
        int n;
        try {
            while ((n = reader.read(cbuf, 0, cbuf.length)) != -1) {
                sb.append(cbuf, 0,  n);
            }
        } catch (IOException e) {
            // ignore 
        }
        return sb.toString().trim();
    }


}
