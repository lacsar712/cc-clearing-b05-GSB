package com.clearing.netting.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出功能验收：
 * 1) 义务导出复用筛选参数（先 OPEN 后 NETTED，文件随筛选变化，不导出全表）
 * 2) 空筛选结果 -> 仅表头空表
 * 3) 批次净头寸导出与该批次详情表格对齐，会员/金额一致且批次隔离
 * 4) viewer 可导出，未登录 401
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportEndpointsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOM = "﻿";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsFollowFiltersAndBatchScope() throws Exception {
        String operator = login("operator", "op123456");
        String viewer = login("viewer", "view123456");

        String m1 = createMember(operator, "Alpha, Bank");
        String m2 = createMember(operator, "Beta Securities");
        String m3 = createMember(operator, "Gamma Clearing");

        LocalDate d1 = LocalDate.of(2026, 9, 21);
        createObligation(operator, m1, m2, "100000", d1);
        createObligation(operator, m2, m3, "60000", d1);
        createObligation(operator, m3, m1, "40000", d1);
        createObligation(operator, m1, m3, "25000", d1);

        // --- 义务导出：OPEN 筛选 ---
        List<List<String>> openCsv = exportObligations(operator, "USD", d1, "OPEN");
        assertEquals(
                List.of("义务 ID", "付款方", "收款方", "币种", "金额", "交割日", "状态"),
                openCsv.get(0));
        assertEquals(4, openCsv.size() - 1);
        for (List<String> row : openCsv.subList(1, openCsv.size())) {
            assertEquals("OPEN", row.get(6), "导出文件中混入了非 OPEN 义务: " + row);
            assertEquals("USD", row.get(3));
            assertEquals(d1.toString(), row.get(5));
        }
        // 含逗号的会员名必须被正确转义并解析回来
        assertTrue(openCsv.stream().skip(1).anyMatch(r -> r.get(1).equals("Alpha, Bank")
                || r.get(2).equals("Alpha, Bank")));
        // 与列表接口行数一致
        int openListCount = listObligationsCount(operator, "USD", d1, "OPEN");
        assertEquals(openListCount, openCsv.size() - 1);

        // --- 此时还没有 NETTED：导出必须是空表（仅表头），禁止偷偷导出全量 ---
        List<List<String>> nettedBefore = exportObligations(operator, "USD", d1, "NETTED");
        assertEquals(1, nettedBefore.size(), "无 NETTED 数据时只能导出表头，实际: " + nettedBefore);

        // --- 执行轧差批次 1 ---
        JsonNode run1 = executeRun(operator, d1, "USD");
        String run1Id = run1.get("run").get("runId").asText();
        Map<String, String> run1Positions = positionMap(run1.get("positions"));
        assertEquals(3, run1Positions.size());

        // --- 义务导出：改筛 NETTED，文件随之变化，且全部为 NETTED ---
        List<List<String>> nettedCsv = exportObligations(operator, "USD", d1, "NETTED");
        assertEquals(4, nettedCsv.size() - 1);
        for (List<String> row : nettedCsv.subList(1, nettedCsv.size())) {
            assertEquals("NETTED", row.get(6));
        }
        Set<String> openIds = idColumn(openCsv);
        Set<String> nettedIds = idColumn(nettedCsv);
        assertEquals(openIds, nettedIds, "同一批义务改状态后 ID 集合应一致");

        // OPEN 筛选现在应为空表
        List<List<String>> openAfter = exportObligations(operator, "USD", d1, "OPEN");
        assertEquals(1, openAfter.size());

        // --- 批次 1 净头寸导出：与详情数据逐列对齐 ---
        List<List<String>> pos1Csv = exportPositions(operator, run1Id);
        assertEquals(List.of("会员 ID", "币种", "净头寸"), pos1Csv.get(0));
        Map<String, String> pos1Map = new LinkedHashMap<>();
        for (List<String> row : pos1Csv.subList(1, pos1Csv.size())) {
            assertEquals("USD", row.get(1));
            pos1Map.put(row.get(0), row.get(2));
        }
        assertEquals(run1Positions.keySet(), pos1Map.keySet());
        for (Map.Entry<String, String> e : pos1Map.entrySet()) {
            assertEquals(0, new java.math.BigDecimal(run1Positions.get(e.getKey())).compareTo(new java.math.BigDecimal(e.getValue())),
                    "会员 " + e.getKey() + " 的净头寸与详情接口不一致");
        }
        // 轧差守恒：m1 收 40000-100000-25000=-85000；m2 收 100000-60000=40000；m3 收 60000+25000-40000=45000
        assertEquals("-85000.00000000", pos1Map.get(m1));
        assertEquals("40000.00000000", pos1Map.get(m2));
        assertEquals("45000.00000000", pos1Map.get(m3));

        // --- 另一个交割日的批次 2：验证导出按批次隔离 ---
        LocalDate d2 = LocalDate.of(2026, 9, 22);
        createObligation(operator, m1, m2, "7000", d2);
        JsonNode run2 = executeRun(operator, d2, "USD");
        String run2Id = run2.get("run").get("runId").asText();
        Map<String, String> run2Positions = positionMap(run2.get("positions"));

        List<List<String>> pos2Csv = exportPositions(operator, run2Id);
        Map<String, String> pos2Map = new LinkedHashMap<>();
        for (List<String> row : pos2Csv.subList(1, pos2Csv.size())) {
            pos2Map.put(row.get(0), row.get(2));
        }
        assertEquals(run2Positions.keySet(), pos2Map.keySet());
        assertEquals(Set.of(m1, m2), pos2Map.keySet(), "批次 2 只应包含当日发生收付的两个会员");
        assertFalse(pos2Map.containsKey(m3), "批次 2 导出泄露了其他批次/会员数据");
        assertEquals("-7000.00000000", pos2Map.get(m1));
        assertEquals("7000.00000000", pos2Map.get(m2));
        // 批次 1 文件不受批次 2 影响
        List<List<String>> pos1Again = exportPositions(operator, run1Id);
        assertEquals(pos1Csv, pos1Again);

        // --- viewer 只读角色也可导出两处数据 ---
        List<List<String>> viewerObligations = exportObligations(viewer, "USD", d1, "NETTED");
        assertEquals(4, viewerObligations.size() - 1);
        List<List<String>> viewerPositions = exportPositions(viewer, run1Id);
        assertEquals(pos1Csv, viewerPositions);

        // --- 未登录拒绝 ---
        mockMvc.perform(get("/api/obligations/export").param("status", "OPEN"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/netting-runs/" + run1Id + "/positions/export"))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String createMember(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("memberId").asText();
    }

    private void createObligation(String token, String payer, String payee, String amount, LocalDate settleDate)
            throws Exception {
        String body = String.format(
                "{\"payerMemberId\":\"%s\",\"payeeMemberId\":\"%s\",\"currency\":\"USD\",\"amount\":%s,"
                        + "\"tradeDate\":\"%s\",\"settleDate\":\"%s\"}",
                payer, payee, amount, settleDate, settleDate);
        mockMvc.perform(post("/api/obligations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    private int listObligationsCount(String token, String currency, LocalDate settleDate, String status)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/obligations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("currency", currency)
                        .param("settleDate", settleDate.toString())
                        .param("status", status))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).size();
    }

    private List<List<String>> exportObligations(String token, String currency, LocalDate settleDate, String status)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/obligations/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("currency", currency)
                        .param("settleDate", settleDate.toString())
                        .param("status", status))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        return parseCsv(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode executeRun(String token, LocalDate settleDate, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/netting-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"settleDate\":\"" + settleDate + "\",\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private List<List<String>> exportPositions(String token, String runId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/netting-runs/" + runId + "/positions/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        return parseCsv(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, String> positionMap(JsonNode positions) {
        Map<String, String> map = new LinkedHashMap<>();
        for (JsonNode p : positions) {
            map.put(p.get("memberId").asText(), p.get("netAmount").asText());
        }
        return map;
    }

    private Set<String> idColumn(List<List<String>> csv) {
        Set<String> ids = new LinkedHashSet<>();
        for (List<String> row : csv.subList(1, csv.size())) {
            ids.add(row.get(0));
        }
        return ids;
    }

    /** 最小 RFC 4180 解析（支持引号、转义双引号、BOM、CRLF）。 */
    private List<List<String>> parseCsv(String content) {
        if (content.startsWith(BOM)) {
            content = content.substring(BOM.length());
        }
        List<List<String>> records = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                field.add(current.toString());
                current.setLength(0);
            } else if (c == '\r') {
                // skip, handled by \n
            } else if (c == '\n') {
                field.add(current.toString());
                current.setLength(0);
                records.add(field);
                field = new ArrayList<>();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0 || !field.isEmpty()) {
            field.add(current.toString());
            records.add(field);
        }
        return records;
    }
}
