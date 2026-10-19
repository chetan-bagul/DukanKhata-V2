package com.dukankhata.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReportSummaryResponse(
        LocalDate from, LocalDate to, int closedDays,
        BigDecimal expectedSales, BigDecimal cash, BigDecimal upi, BigDecimal udhar,
        BigDecimal customerRepaymentCash, BigDecimal customerRepaymentUpi,
        BigDecimal actualCollection, BigDecimal variance, BigDecimal expenses, BigDecimal netProfit,
        BigDecimal posSalesTotal, BigDecimal posCash, BigDecimal posUpi, BigDecimal posUdhar) {}
