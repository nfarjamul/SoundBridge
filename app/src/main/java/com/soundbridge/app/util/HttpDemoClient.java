package com.soundbridge.app.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Isolated HTTPS GET networking demonstration for course requirements:
 * 1) Real HTTPS GET returning JSON parsed with org.json (JSONObject / JSONArray).
 * 2) Real HTTPS GET returning XML parsed with XmlPullParser.
 * Includes finite timeouts, HTTP status checking, background thread execution, and offline resilience.
 */
public class HttpDemoClient {
    // Stable public HTTPS endpoints returning JSON and XML without API keys
    public static final String DEFAULT_JSON_URL = "https://jsonplaceholder.typicode.com/todos/1";
    public static final String DEFAULT_XML_URL = "https://www.w3schools.com/xml/note.xml";

    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface ApiResultCallback {
        void onSuccess(int httpStatus, String formattedSummary, String rawBodyPreview);
        void onError(String friendlyMessage);
    }

    public HttpDemoClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void runJsonGetDemo(final String url, final ApiResultCallback callback) {
        executor.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Accept", "application/json")
                        .get()
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        postError(callback, "HTTPS GET failed with HTTP status " + code + ". Core on-device sound detection remains unaffected.");
                        return;
                    }
                    String summary = parseJsonSummary(body);
                    String preview = body.length() > 600 ? body.substring(0, 600) + "..." : body;
                    mainHandler.post(() -> callback.onSuccess(code, summary, preview));
                }
            } catch (IOException e) {
                postError(callback, "Network/Offline Error (" + e.getClass().getSimpleName() + "): " + e.getMessage()
                        + "\n\nNote: Core sound recognition runs 100% offline on-device and does not depend on network connectivity.");
            } catch (Exception e) {
                postError(callback, "JSON Parsing Error: " + e.getMessage());
            }
        });
    }

    public void runXmlGetDemo(final String url, final ApiResultCallback callback) {
        executor.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Accept", "application/xml, text/xml")
                        .get()
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        postError(callback, "HTTPS GET failed with HTTP status " + code + ". Core on-device sound detection remains unaffected.");
                        return;
                    }
                    String summary = parseXmlWithPullParser(body);
                    String preview = body.length() > 600 ? body.substring(0, 600) + "..." : body;
                    mainHandler.post(() -> callback.onSuccess(code, summary, preview));
                }
            } catch (IOException e) {
                postError(callback, "Network/Offline Error (" + e.getClass().getSimpleName() + "): " + e.getMessage()
                        + "\n\nNote: Core sound recognition runs 100% offline on-device and does not depend on network connectivity.");
            } catch (Exception e) {
                postError(callback, "XML Parsing Error: " + e.getMessage());
            }
        });
    }

    /**
     * Parses a JSON response using org.json (JSONObject / JSONArray) into a human-readable summary.
     */
    public static String parseJsonSummary(String jsonStr) throws JSONException {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            throw new JSONException("Empty JSON response");
        }
        String trimmed = jsonStr.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Parsed with org.json:\n");
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            sb.append("• Root Type: JSONArray (length=").append(arr.length()).append(")\n");
            if (arr.length() > 0 && arr.optJSONObject(0) != null) {
                JSONObject first = arr.getJSONObject(0);
                appendJsonObjectFields(sb, first);
            }
        } else {
            JSONObject obj = new JSONObject(trimmed);
            sb.append("• Root Type: JSONObject\n");
            appendJsonObjectFields(sb, obj);
        }
        return sb.toString().trim();
    }

    private static void appendJsonObjectFields(StringBuilder sb, JSONObject obj) {
        JSONArray names = obj.names();
        if (names == null) return;
        int count = Math.min(6, names.length());
        for (int i = 0; i < count; i++) {
            String key = names.optString(i);
            Object val = obj.opt(key);
            sb.append(String.format(Locale.US, "• %s: %s\n", key, String.valueOf(val)));
        }
    }

    /**
     * Parses an XML response using Android's XmlPullParser into a structured tag/value summary.
     */
    public static String parseXmlWithPullParser(String xmlStr) throws Exception {
        if (xmlStr == null || xmlStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty XML response");
        }
        XmlPullParser parser;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            parser = factory.newPullParser();
        } catch (Throwable t) {
            parser = Xml.newPullParser();
        }
        parser.setInput(new StringReader(xmlStr.trim()));

        List<String> extractedPairs = new ArrayList<>();
        String rootTag = null;
        String currentTag = null;
        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                currentTag = parser.getName();
                if (rootTag == null) {
                    rootTag = currentTag;
                }
            } else if (eventType == XmlPullParser.TEXT) {
                String text = parser.getText();
                if (currentTag != null && text != null && !text.trim().isEmpty()) {
                    extractedPairs.add("• <" + currentTag + ">: " + text.trim());
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                currentTag = null;
            }
            eventType = parser.next();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Parsed with XmlPullParser:\n");
        sb.append("• Root Element: <").append(rootTag != null ? rootTag : "xml").append(">\n");
        int maxItems = Math.min(8, extractedPairs.size());
        for (int i = 0; i < maxItems; i++) {
            sb.append(extractedPairs.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private void postError(final ApiResultCallback callback, final String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(msg));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

