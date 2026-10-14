package com.premisave.wallet.commercial.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Holds the request body in memory so it can be hashed and still be read again by the controller. */
public class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    public byte[] body() { return body; }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            @Override public int read() { return in.read(); }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
