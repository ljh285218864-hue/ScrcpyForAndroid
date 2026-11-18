package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiResponsePolicyTest {
    @Test public void acceptsJsonAndCharset() {
        assertTrue(ApiResponsePolicy.isJsonResponse("application/json"));
        assertTrue(ApiResponsePolicy.isJsonResponse("application/json; charset=utf-8"));
        assertTrue(ApiResponsePolicy.isJsonResponse("Application/JSON; charset=UTF-8"));
    }

    @Test public void rejectsNginxOrWordpressHtml() {
        assertFalse(ApiResponsePolicy.isJsonResponse("text/html"));
        assertFalse(ApiResponsePolicy.isJsonResponse("text/html; charset=UTF-8"));
        assertFalse(ApiResponsePolicy.isJsonResponse("application/xhtml+xml"));
        assertFalse(ApiResponsePolicy.isJsonResponse("text/plain"));
        assertFalse(ApiResponsePolicy.isJsonResponse(null));
        assertFalse(ApiResponsePolicy.isJsonResponse("application/jsonp"));
    }
}
