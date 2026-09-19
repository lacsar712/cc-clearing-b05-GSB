package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.application.ObligationApplicationService;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/obligations")
public class ObligationController {

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

    /**
     * CSV export of the obligation list. Reuses the exact same currency/settleDate/status
     * filters as {@link #list} so the file always matches the on-screen query; when the
     * filtered result is empty a header-only CSV is returned (never the full table).
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
            @RequestParam(required = false) ObligationStatus status) {
        AuthContext.require();
        List<TradeObligation> obligations = obligationService.list(currency, settleDate, status);
        List<List<String>> rows = obligations.stream()
                .map(o -> List.of(
                        o.getObligationId(),
                        o.getPayerMemberId(),
                        o.getPayeeMemberId(),
                        o.getCurrency(),
                        o.getAmount().toPlainString(),
                        o.getSettleDate().toString(),
                        o.getStatus().name()))
                .collect(Collectors.toList());
        byte[] csv = CsvUtils.toCsvBytes(
                List.of("义务 ID", "付款方", "收款方", "币种", "金额", "交割日", "状态"), rows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"obligations.csv\"")
                .body(csv);
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
