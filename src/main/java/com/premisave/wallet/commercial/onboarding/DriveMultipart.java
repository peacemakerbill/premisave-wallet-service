package com.premisave.wallet.commercial.onboarding;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The multipart/related body Drive expects for a one request upload of metadata and content. */
public final class DriveMultipart {

    private DriveMultipart() {}

    public static String contentType(String boundary) {
        return "multipart/related; boundary=" + boundary;
    }

    public static byte[] body(String boundary, String metadataJson, String fileContentType, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + metadataJson.length() + 256);
        write(out, "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadataJson + "\r\n");
        write(out, "--" + boundary + "\r\nContent-Type: " + fileContentType + "\r\n\r\n");
        out.writeBytes(data);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
