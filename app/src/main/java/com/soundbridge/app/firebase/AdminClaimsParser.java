package com.soundbridge.app.firebase;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Pure-Java helpers for reading Firebase ID token (JWT) payload claims and the protected
 * adminUsers/{uid} Firestore document.
 *
 * NOTE: Client-side claim parsing only decides what UI to show. Actual authorization is enforced
 * server-side by firestore.rules, which validate the signed token (request.auth.token.admin)
 * or the existence of adminUsers/{uid} before allowing any calibration write.
 */
public final class AdminClaimsParser {

    private AdminClaimsParser() {
    }

    public static JSONObject decodeJwtPayload(String idToken) {
        if (idToken == null) return null;
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True if the token carries a Firebase custom claim admin == true or role == "admin".
     */
    public static boolean hasAdminCustomClaim(String idToken) {
        return hasAdminClaimInPayload(decodeJwtPayload(idToken));
    }

    public static boolean hasAdminClaimInPayload(JSONObject payload) {
        if (payload == null) return false;
        if (payload.optBoolean("admin", false)) return true;
        if (payload.optInt("admin", 0) == 1) return true;
        return "admin".equalsIgnoreCase(payload.optString("role", ""));
    }

    public static String buildTestJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + body + ".sig";
    }

    /**
     * Returns the token expiry (exp claim) in epoch ms, or 0 if unavailable.
     */
    public static long getTokenExpiryMs(String idToken) {
        JSONObject payload = decodeJwtPayload(idToken);
        if (payload == null) return 0L;
        return payload.optLong("exp", 0L) * 1000L;
    }

    /**
     * Evaluates Firestore REST fields of adminUsers/{uid}: requires role == "admin" or isAdmin == true,
     * and active != false.
     */
    public static boolean isAdminUserDocument(JSONObject firestoreFields) {
        if (firestoreFields == null) return false;
        boolean active = CalibrationFirestoreCodec.getBool(firestoreFields, "active", true);
        if (!active) return false;
        boolean isAdminFlag = CalibrationFirestoreCodec.getBool(firestoreFields, "isAdmin", false);
        String role = CalibrationFirestoreCodec.getStr(firestoreFields, "role", "");
        return isAdminFlag || "admin".equalsIgnoreCase(role);
    }

    private static String padBase64(String s) {
        int rem = s.length() % 4;
        if (rem == 2) return s + "==";
        if (rem == 3) return s + "=";
        return s;
    }
}

