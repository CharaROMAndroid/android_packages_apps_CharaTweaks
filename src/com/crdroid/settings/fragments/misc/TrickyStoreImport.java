/*
 * SPDX-FileCopyrightText: 2026 CharaROM
 * SPDX-License-Identifier: Apache-2.0
 */

package com.crdroid.settings.fragments.misc;

import android.security.trickystore.KeyBoxManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded, side-effect-free document validation. Call on a worker thread. */
final class TrickyStoreImport {
    static final int KEYBOX_LIMIT = 256 * 1024;
    static final int TARGET_LIMIT = 64 * 1024;
    private static final Pattern PACKAGE = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

    private TrickyStoreImport() {}

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("Unable to open document");
        try (InputStream stream = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            // Probe one byte beyond the limit, never allocate for the entire document.
            while ((count = stream.read(buffer, 0,
                    Math.min(buffer.length, limit - out.size() + 1))) != -1) {
                if (count == 0) {
                    int value = stream.read();
                    if (value == -1) break;
                    if (out.size() == limit) throw new IOException("Document is too large");
                    out.write(value);
                    continue;
                }
                if (count > limit - out.size()) throw new IOException("Document is too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    static void validateKeybox(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > KEYBOX_LIMIT) {
            throw new IOException("Empty or oversized keybox");
        }
        String xml = decode(bytes);
        // Do not pass DTDs/entities to the framework parser. Inspect tokens, not
        // substrings (comments and CDATA are not declarations).
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(xml));
        List<String> parents = new ArrayList<>();
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                event = parser.nextToken()) {
            if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF
                    || parser.getDepth() > 32) {
                throw new IOException("Unsupported keybox XML");
            }
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                String parent = parents.isEmpty() ? "" : parents.get(parents.size() - 1);
                boolean valid;
                switch (tag) {
                    case "AndroidAttestation":
                        valid = parent.isEmpty();
                        break;
                    case "Keybox":
                        valid = parent.isEmpty() || parent.equals("AndroidAttestation");
                        break;
                    case "Key":
                        valid = parent.isEmpty() || parent.equals("Keybox")
                                || parent.equals("AndroidAttestation");
                        break;
                    case "PrivateKey":
                    case "CertificateChain":
                        valid = parent.equals("Key");
                        break;
                    case "Certificate":
                    case "NumberOfCertificates":
                        valid = parent.equals("CertificateChain");
                        break;
                    case "NumberOfKeyboxes":
                        valid = parent.equals("AndroidAttestation");
                        break;
                    default:
                        valid = false;
                }
                if (!valid) throw new IOException("Invalid keybox XML structure");
                parents.add(tag);
            } else if (event == XmlPullParser.END_TAG) {
                parents.remove(parents.size() - 1);
            }
        }
        // Use an isolated instance, never the running service's active keys.
        // This checks real private keys, certificates and their correspondence.
        KeyBoxManager validator = new KeyBoxManager();
        try {
            if (!validator.parseKeybox(xml) || !validator.hasKeyboxes()) {
                throw new IOException("Invalid keybox keys or certificates");
            }
        } finally {
            validator.clear();
        }
    }

    static String validateTargets(byte[] bytes) throws IOException {
        if (bytes.length > TARGET_LIMIT) throw new IOException("Target list is too large");
        String text = decode(bytes);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw new IOException("Invalid target list character");
            }
        }
        // Keep the current screen's JSON target format as well as line-based imports.
        if (text.trim().startsWith("[")) {
            try {
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.getJSONObject(i);
                    String name = entry.getString("package");
                    if (name.length() > 255 || !PACKAGE.matcher(name).matches()) {
                        throw new IOException("Invalid target package name");
                    }
                    String mode = entry.optString("mode", "").toUpperCase(java.util.Locale.ROOT);
                    if (!mode.isEmpty() && !java.util.Arrays.asList(
                            "AUTO", "LEAF_HACK", "GENERATE", "SKIP").contains(mode)) {
                        throw new IOException("Invalid target mode");
                    }
                }
                return text;
            } catch (org.json.JSONException e) {
                throw new IOException("Invalid target JSON", e);
            }
        }
        for (String raw : text.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            char suffix = line.charAt(line.length() - 1);
            String name = suffix == '?' || suffix == '!' || suffix == '-'
                    ? line.substring(0, line.length() - 1).trim() : line;
            if (name.length() > 255 || !PACKAGE.matcher(name).matches()) {
                throw new IOException("Invalid target package name");
            }
        }
        // An empty list intentionally clears explicit targets, as in the app picker.
        return text;
    }
}
