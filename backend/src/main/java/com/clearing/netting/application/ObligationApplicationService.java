package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ObligationApplicationService {

    private final ObligationRepositoryPort obligationRepository;
    private final MemberRepositoryPort memberRepository;

    public ObligationApplicationService(
            ObligationRepositoryPort obligationRepository,
            MemberRepositoryPort memberRepository) {
        this.obligationRepository = obligationRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public List<TradeObligation> list(String currency, LocalDate settleDate, ObligationStatus status) {
        return obligationRepository.findByFilters(currency, settleDate, status);
    }

    /**
     * 导出与列表完全一致的筛选结果（同币种/交割日/状态参数），
     * 并附带会员名称，避免导出与页面展示不一致。
     */
    @Transactional(readOnly = true)
    public List<ObligationExportRow> exportRows(String currency, LocalDate settleDate, ObligationStatus status) {
        List<TradeObligation> obligations = obligationRepository.findByFilters(currency, settleDate, status);
        Set<String> memberIds = new HashSet<>();
        for (TradeObligation o : obligations) {
            memberIds.add(o.getPayerMemberId());
            memberIds.add(o.getPayeeMemberId());
        }
        Map<String, String> names = new HashMap<>();
        for (Member m : memberRepository.findByIds(memberIds)) {
            names.put(m.getMemberId(), m.getName());
        }
        return obligations.stream()
                .map(o -> new ObligationExportRow(
                        o,
                        names.getOrDefault(o.getPayerMemberId(), o.getPayerMemberId()),
                        names.getOrDefault(o.getPayeeMemberId(), o.getPayeeMemberId())))
                .collect(java.util.stream.Collectors.toList());
    }

    public record ObligationExportRow(TradeObligation obligation, String payerName, String payeeName) {
    }

    @Transactional
    public TradeObligation create(
            String payerMemberId,
            String payeeMemberId,
            String currency,
            BigDecimal amount,
            LocalDate tradeDate,
            LocalDate settleDate) {
        validateMember(payerMemberId);
        validateMember(payeeMemberId);
        TradeObligation obligation = TradeObligation.open(
                payerMemberId, payeeMemberId, currency, amount, tradeDate, settleDate);
        return obligationRepository.save(obligation);
    }

    private void validateMember(String memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId));
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new DomainException("SUSPENDED_MEMBER", "cannot create obligation for suspended member: " + memberId);
        }
    }
}
