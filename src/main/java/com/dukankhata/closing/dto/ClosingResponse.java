package com.dukankhata.closing.dto;

import com.dukankhata.closing.DailyClosing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClosingResponse(
        UUID id,
        LocalDate businessDate,
        String status,
        BigDecimal expectedSales,
        BigDecimal cash,
        BigDecimal upi,
        BigDecimal udhar,
        BigDecimal customerRepaymentCash,
        BigDecimal customerRepaymentUpi,
        BigDecimal actualCollection,
        BigDecimal variance,
        BigDecimal expensesTotal,
        BigDecimal netProfit,
        Boolean posAutoAggregated,
        Long posSaleCount,
        BigDecimal posSalesTotal,
        BigDecimal posCash,
        BigDecimal posUpi,
        BigDecimal posUdhar,
        String notes,
        Instant closedAt,
        List<ClosingItemResponse> items,
        List<ClosingExpenseResponse> expenses) {

    public static ClosingResponse from(DailyClosing closing) {
        return new ClosingResponse(
                closing.getId(), closing.getBusinessDate(), closing.getStatus().name(),
                closing.getExpectedSales(), closing.getCash(), closing.getUpi(), closing.getUdhar(),
                closing.getCustomerRepaymentCash(), closing.getCustomerRepaymentUpi(),
                closing.getActualCollection(), closing.getVariance(), closing.getExpensesTotal(),
                closing.getNetProfit(), closing.getPosAutoAggregated(), closing.getPosSaleCount(),
                closing.getPosSalesTotal(), closing.getPosCash(), closing.getPosUpi(), closing.getPosUdhar(),
                closing.getNotes(), closing.getClosedAt(),
                closing.getItems().stream().map(item -> new ClosingItemResponse(
                        item.getProduct().getId(), item.getProductNameSnapshot(), item.getUnitPriceSnapshot(),
                        item.getQuantity(), item.getAmount())).toList(),
                closing.getExpenses().stream().map(expense -> new ClosingExpenseResponse(
                        expense.getId(), expense.getDescription(), expense.getAmount())).toList());
    }
}
