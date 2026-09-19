package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.adapter.in.web.support.CsvWriter;
import com.clearing.netting.application.ObligationApplicationService;
import com.clearing.netting.application.ObligationApplicationService.ObligationExportRow;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/obligations")
public class ObligationController {

    /** 与义务页表格列一一对应。 */
    private static final String[] EXPORT_HEADERS = {
            "义务 ID", "付款方", "收款方", "币种", "金额", "交割日", "状态"
    };

    private final ObligationApplicationService obligationService;

    public ObligationController(ObligationApplicationService obligationService) {
        this.obligationService = obligationService;
    }

    @GetMapping
    public List<ObligationResponse> list(
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
            @RequestParam(required = false) ObligationStatus status) {
        AuthContext.require();
        return obligationService.list(currency, settleDate, status).stream()
                .map(ObligationResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 义务导出：独立下载接口，复用与列表完全相同的币种/交割日/状态筛选；
     * 无筛选结果时只输出表头，绝不回退到全表。只读接口，viewer 也可调用。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
            @RequestParam(required = false) ObligationStatus status) throws Exception {
        AuthContext.require();
        List<ObligationExportRow> rows = obligationService.exportRows(currency, settleDate, status);

        StringWriter writer = new StringWriter();
        CsvWriter.write(writer, EXPORT_HEADERS, () -> rows.stream().map(row -> {
            TradeObligation o = row.obligation();
            return new String[] {
                    o.getObligationId(),
                    row.payerName(),
                    row.payeeName(),
                    o.getCurrency(),
                    o.getAmount().toPlainString(),
                    o.getSettleDate().toString(),
                    o.getStatus().name()
            };
        }).iterator());

        String filename = "obligations"
                + (currency == null || currency.isBlank() ? "" : "_" + currency.trim().toUpperCase())
                + (settleDate == null ? "" : "_" + settleDate)
                + (status == null ? "" : "_" + status.name())
                + ".csv";
        return CsvWriter.download(filename, writer.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping
    public ObligationResponse create(@Valid @RequestBody CreateObligationRequest request) {
        AuthContext.requireOperator();
        return ObligationResponse.from(obligationService.create(
                request.payerMemberId(),
                request.payeeMemberId(),
                request.currency(),
                request.amount(),
                request.tradeDate(),
                request.settleDate()));
    }

    public record CreateObligationRequest(
            @NotBlank String payerMemberId,
            @NotBlank String payeeMemberId,
            @NotBlank String currency,
            @NotNull @DecimalMin("0.00000001") BigDecimal amount,
            @NotNull LocalDate tradeDate,
            @NotNull LocalDate settleDate) {
    }

    public record ObligationResponse(
            String obligationId,
            String payerMemberId,
            String payeeMemberId,
            String currency,
            BigDecimal amount,
            LocalDate tradeDate,
            LocalDate settleDate,
            ObligationStatus status,
            String nettingRunId) {
        static ObligationResponse from(TradeObligation o) {
            return new ObligationResponse(
                    o.getObligationId(),
                    o.getPayerMemberId(),
                    o.getPayeeMemberId(),
                    o.getCurrency(),
                    o.getAmount(),
                    o.getTradeDate(),
                    o.getSettleDate(),
                    o.getStatus(),
                    o.getNettingRunId());
        }
    }
}
