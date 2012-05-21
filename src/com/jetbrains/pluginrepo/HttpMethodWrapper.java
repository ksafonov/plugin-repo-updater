package com.jetbrains.pluginrepo;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * User: ksafonov
 */
public abstract class HttpMethodWrapper {

    private static final int RETRY_COUNT = 3;

    private final HttpClient myHttpClient;

    private boolean myExecuted;
    private int myResponseCode;
    private String myResponseBody;

    protected HttpMethodWrapper(HttpClient myHttpClient) {
        this.myHttpClient = myHttpClient;
    }

    protected abstract HttpMethod createMethod();

    public int getStatusCode() {
        if (!myExecuted) {
            throw new IllegalStateException("Call execute() first");
        }
        return myResponseCode;
    }

    public String getResponseBody() {
        if (!myExecuted) {
            throw new IllegalStateException("Call execute() first");
        }
        return myResponseBody;
    }

    public void execute() throws IOException {
        for (int i = 0; i < RETRY_COUNT; i++) {
            HttpMethod method = createMethod();
            method.setFollowRedirects(false);
            myResponseCode = myHttpClient.executeMethod(method);
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            // to prevent warning from HttpClient
            copyStreamContent(method.getResponseBodyAsStream(), s);
            myResponseBody = new String(s.toByteArray(), Charset.forName("UTF-8"));

            if (myResponseCode != HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                break;
            }
        }
        myExecuted = true;
    }

    private static int copyStreamContent(InputStream inputStream, OutputStream outputStream) throws IOException {
        final byte[] buffer = new byte[10 * 1024];
        int count;
        int total = 0;
        while ((count = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, count);
            total += count;
        }
        return total;
    }

}
