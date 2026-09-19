package com.clearing.netting.adapter.in.web.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 最小化 RFC 4180 风格 CSV 工具，仅依赖 JDK 标准库与 Spring 的响应类型。
 * 输出带 UTF-8 BOM，保证 Excel 直接打开中文表头不乱码。
 */
public final class CsvWriter {

    /** UTF-8 BOM，让 Excel 按 UTF-8 识别编码。 */
    private static final char BOM = '﻿';

    private CsvWriter() {
    }

    public static void write(Writer writer, String[] header, Iterable<String[]> rows) throws IOException {
        writer.write(BOM);
        writeLine(writer, header);
        for (String[] row : rows) {
            writeLine(writer, row);
        }
    }

    /** 构造带 Content-Disposition 的 CSV 下载响应（文件名同时提供 ASCII 回退与 UTF-8 编码）。 */
    public static ResponseEntity<byte[]> download(String filename, byte[] body) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static void writeLine(Writer writer, String[] fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escape(fields[i]));
        }
        writer.write("\r\n");
    }

    private static String escape(String raw) {
        String value = raw == null ? "" : raw;
        boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
