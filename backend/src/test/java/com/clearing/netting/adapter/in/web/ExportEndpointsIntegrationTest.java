package com.clearing.netting.adapter.in.web;

import com.clearing.netting.application.MemberApplicationService;
import com.clearing.netting.application.NettingApplicationService;
import com.clearing.netting.application.ObligationApplicationService;
import com.clearing.netting.application.TokenService;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.TradeObligation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the CSV export endpoints stay consistent with the on-screen queries:
 * filtered obligation export, per-run net-position export, empty-filter behaviour,
 * and that read-only (viewer) users may export.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:exporttest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class ExportEndpointsIntegrationTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 21);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokenService;

    @Autowired
    MemberApplicationService memberService;

    @Autowired
    ObligationApplicationService obligationService;

    @Autowired
    NettingApplicationService nettingService;

    private String viewerToken;
    private Member alpha;
    private Member beta;

    @BeforeEach
    void setUp() {
        viewerToken = tokenService.issueToken("viewer", "VIEWER");
        alpha = memberService.createMember("Alpha Bank " + suffix());
        beta = memberService.createMember("Beta Bank " + suffix());
    }

    private String suffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private String bearer() {
        return "Bearer " + viewerToken;
    }

    private List<String[]> parseCsv(String body) {
        String stripped = body.replace("\uFEFF", "");
        List<String[]> rows = new ArrayList<>();
        for (String line : stripped.split("\r\n")) {
            if (!line.isEmpty()) {
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }

    private String exportObligations(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/obligations/export" + query)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void obligationExportFollowsStatusFilterAndSwitchesWithIt() throws Exception {
        TradeObligation o1 = obligationService.create(
                alpha.getMemberId(), beta.getMemberId(), "USD",
                new BigDecimal("100"), SETTLE_DATE.minusDays(1), SETTLE_DATE);
        TradeObligation o2 = obligationService.create(
                beta.getMemberId(), alpha.getMemberId(), "USD",
                new BigDecimal("40"), SETTLE_DATE.minusDays(1), SETTLE_DATE);

        // 1. filter OPEN -> file contains only OPEN rows, with Chinese headers
        String openCsv = exportObligations("?status=OPEN&currency=USD&settleDate=" + SETTLE_DATE);
        List<String[]> openRows = parseCsv(openCsv);
        assertThat(openRows.get(0)).containsExactly("义务 ID", "付款方", "收款方", "币种", "金额", "交割日", "状态");
        assertThat(openRows).hasSize(3); // header + 2 rows
        assertThat(openRows.subList(1, 3))
                .allSatisfy(row -> {
                    assertThat(row[6]).isEqualTo("OPEN");
                    assertThat(row[3]).isEqualTo("USD");
                    assertThat(row[5]).isEqualTo(SETTLE_DATE.toString());
                });
        assertThat(openCsv).contains(o1.getObligationId(), o2.getObligationId());

        // 2. net the obligations, then re-export with NETTED -> content changes
        NettingApplicationService.NettingRunResult result = nettingService.execute(SETTLE_DATE, "USD");

        String nettedCsv = exportObligations("?status=NETTED&currency=USD&settleDate=" + SETTLE_DATE);
        List<String[]> nettedRows = parseCsv(nettedCsv);
        assertThat(nettedCsv).isNotEqualTo(openCsv);
        assertThat(nettedRows).hasSize(3);
        assertThat(nettedRows.subList(1, 3)).allSatisfy(row -> assertThat(row[6]).isEqualTo("NETTED"));

        // OPEN filter now matches nothing -> header-only CSV, not the full table
        String openAfter = exportObligations("?status=OPEN&currency=USD&settleDate=" + SETTLE_DATE);
        assertThat(parseCsv(openAfter)).hasSize(1);

        // 3. positions export for the run: columns align with the detail page table
        String runId = result.run().getRunId();
        MvcResult posResult = mockMvc.perform(get("/api/netting-runs/{id}/positions/export", runId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
        List<String[]> posRows = parseCsv(posResult.getResponse().getContentAsString());
        assertThat(posRows.get(0)).containsExactly("会员 ID", "币种", "净头寸");

        List<NetPosition> positions = nettingService.getPositions(runId);
        assertThat(posRows).hasSize(positions.size() + 1);
        for (NetPosition p : positions) {
            assertThat(posRows.subList(1, posRows.size()))
                    .anySatisfy(row -> {
                        assertThat(row[0]).isEqualTo(p.getMemberId());
                        assertThat(row[1]).isEqualTo(p.getCurrency());
                        assertThat(new BigDecimal(row[2])).isEqualByComparingTo(p.getNetAmount());
                    });
        }
        // net amounts sum to zero, members and amounts line up
        BigDecimal sum = positions.stream()
                .map(NetPosition::getNetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void emptyFilterResultExportsHeaderOnlyCsv() throws Exception {
        String csv = exportObligations("?status=CANCELLED&currency=EUR&settleDate=" + SETTLE_DATE);
        List<String[]> rows = parseCsv(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("义务 ID", "付款方", "收款方", "币种", "金额", "交割日", "状态");
    }

    @Test
    void exportRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/obligations/export"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/netting-runs/{id}/positions/export", "whatever"))
                .andExpect(status().isUnauthorized());
    }
}
