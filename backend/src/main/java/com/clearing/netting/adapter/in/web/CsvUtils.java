package com.clearing.netting.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal CSV writer backed only by the JDK (RFC 4180 style quoting).
 * A UTF-8 BOM is prepended so spreadsheet tools render Chinese headers correctly.
 */
public final class CsvUtils {

    private static final String UTF8_BOM = "\uFEFF";

    private CsvUtils() {
    }

    public static byte[] toCsvBytes(List<String> headers, List<? extends List<String>> rows) {
        StringBuilder sb = new StringBuilder(UTF8_BOM);
        appendLine(sb, headers);
        for (List<String> row : rows) {
            appendLine(sb, row);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
