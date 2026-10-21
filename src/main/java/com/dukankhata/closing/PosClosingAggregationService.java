package com.dukankhata.closing;

import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.common.time.BusinessTime;
import com.dukankhata.pos.PaymentMethod;
import com.dukankhata.pos.SaleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Service
public class PosClosingAggregationService {
    private final SaleRepository saleRepository;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;

    public PosClosingAggregationService(SaleRepository saleRepository,
                                        CurrentUserService currentUserService,
                                        BusinessTime businessTime) {
        this.saleRepository = saleRepository;
        this.currentUserService = currentUserService;
        this.businessTime = businessTime;
    }

    public PosTotals aggregate(LocalDate businessDate) {
        UUID shopId = currentUserService.shopId();
        var range = businessTime.range(businessDate, businessDate);
        BigDecimal total = money(saleRepository.sumTotal(shopId, range.fromInclusive(), range.toExclusive()));
        BigDecimal cash = money(saleRepository.sumPayment(shopId, PaymentMethod.CASH, range.fromInclusive(), range.toExclusive()));
        BigDecimal upi = money(saleRepository.sumPayment(shopId, PaymentMethod.UPI, range.fromInclusive(), range.toExclusive()));
        BigDecimal udhar = money(saleRepository.sumPayment(shopId, PaymentMethod.UDHAR, range.fromInclusive(), range.toExclusive()));
        long count = saleRepository.countCompletedByShopIdAndCreatedAtBetween(shopId, range.fromInclusive(), range.toExclusive());
        return new PosTotals(total, cash, upi, udhar, count);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public record PosTotals(BigDecimal total, BigDecimal cash, BigDecimal upi, BigDecimal udhar, long saleCount) {}
}
