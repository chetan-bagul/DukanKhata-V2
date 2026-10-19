package com.dukankhata.report;

import com.dukankhata.closing.ClosingStatus;
import com.dukankhata.closing.DailyClosing;
import com.dukankhata.closing.DailyClosingRepository;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.report.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReportService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final DailyClosingRepository closingRepository;
    private final CurrentUserService currentUser;
    private final ReportPdfService pdfService;

    public ReportService(DailyClosingRepository closingRepository, CurrentUserService currentUser, ReportPdfService pdfService) {
        this.closingRepository = closingRepository; this.currentUser = currentUser; this.pdfService = pdfService;
    }

    @Transactional(readOnly = true)
    public ReportResponse report(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<DailyClosing> closings = closingRepository.findByShopIdAndBusinessDateBetweenAndStatusOrderByBusinessDateAsc(
                currentUser.shopId(), from, to, ClosingStatus.CLOSED);
        return build(from, to, closings);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<DailyClosing> closings = closingRepository.findByShopIdAndBusinessDateBetweenAndStatusOrderByBusinessDateAsc(
                currentUser.shopId(), from, to, ClosingStatus.CLOSED);
        return pdfService.generate(currentUser.requireUser().getShop().getName(), build(from, to, closings));
    }

    private ReportResponse build(LocalDate from, LocalDate to, List<DailyClosing> c) {
        BigDecimal expected=ZERO,cash=ZERO,upi=ZERO,udhar=ZERO,repaymentCash=ZERO,repaymentUpi=ZERO;
        BigDecimal actual=ZERO,variance=ZERO,expenses=ZERO,profit=ZERO,posTotal=ZERO,posCash=ZERO,posUpi=ZERO,posUdhar=ZERO;
        var days = new java.util.ArrayList<DailyReportRow>();
        for (DailyClosing x : c) {
            expected=expected.add(x.getExpectedSales()); cash=cash.add(x.getCash()); upi=upi.add(x.getUpi()); udhar=udhar.add(x.getUdhar());
            repaymentCash=repaymentCash.add(x.getCustomerRepaymentCash()); repaymentUpi=repaymentUpi.add(x.getCustomerRepaymentUpi());
            actual=actual.add(x.getActualCollection()); variance=variance.add(x.getVariance()); expenses=expenses.add(x.getExpensesTotal()); profit=profit.add(x.getNetProfit());
            posTotal=posTotal.add(x.getPosSalesTotal()); posCash=posCash.add(x.getPosCash()); posUpi=posUpi.add(x.getPosUpi()); posUdhar=posUdhar.add(x.getPosUdhar());
            days.add(new DailyReportRow(x.getBusinessDate(),x.getExpectedSales(),x.getCash(),x.getUpi(),x.getUdhar(),
                    x.getCustomerRepaymentCash(),x.getCustomerRepaymentUpi(),x.getActualCollection(),x.getVariance(),
                    x.getExpensesTotal(),x.getNetProfit(),x.getPosSalesTotal(),x.getPosCash(),x.getPosUpi(),x.getPosUdhar()));
        }
        return new ReportResponse(new ReportSummaryResponse(from,to,c.size(),expected,cash,upi,udhar,
                repaymentCash,repaymentUpi,actual,variance,expenses,profit,posTotal,posCash,posUpi,posUdhar), days);
    }
    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("Both from and to dates are required");
        if (from.isAfter(to)) throw new IllegalArgumentException("From date cannot be after to date");
        if (from.plusMonths(12).isBefore(to)) throw new IllegalArgumentException("Report date range cannot exceed 12 months");
    }
}
