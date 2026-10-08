package com.midori.playmask;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for email pattern matching and masking.
 * Standalone with zero Xposed dependencies.
 */
public final class EmailMaskUtils {
    private EmailMaskUtils() {}

    // Regex pattern matching email addresses (including non-breaking hyphens \u2011 from OneGoogle)
    public static final Pattern EMAIL_PATTERN = Pattern.compile(
        "([a-zA-Z0-9_.+\\u2011-]+)(@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+)"
    );

    /**
     * Replaces the username before @ with asterisks, preserving the domain.
     * e.g. "username@gmail.com" -> "***@gmail.com"
     */
    public static String maskEmail(String text) {
        if (text == null || text.indexOf('@') == -1) {
            return text;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.replaceAll("***$2");
        }
        return text;
    }

    /**
     * Checks if the given text contains a valid email address.
     */
    public static boolean containsEmail(String text) {
        if (text == null || text.indexOf('@') == -1) {
            return false;
        }
        return EMAIL_PATTERN.matcher(text).find();
    }
}
