package com.dukankhata.closing;

import com.dukankhata.closing.dto.ClosingExpenseRequest;
import com.dukankhata.closing.dto.ClosingItemRequest;
import com.dukankhata.closing.dto.ClosingResponse;
import com.dukankhata.closing.dto.ClosingRequest;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.customer.CustomerLedgerRepository;
import com.dukankhata.pos.PaymentMethod;
import com.dukankhata.product.Product;
import com.dukankhata.product.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class DailyClosingService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final DailyClosingRepository closingRepository;
    private final ProductRepository productRepository;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final CurrentUserService currentUser;
    private final PosClosingAggregationService posAggregation;
    private final ZoneId businessZone;

    public DailyClosingService(DailyClosingRepository closingRepository,
                               ProductRepository productRepository,
                               CustomerLedgerRepository customerLedgerRepository,
                               CurrentUserService currentUser,
                               PosClosingAggregationService posAggregation,
                               @Value("${app.business-time-zone:Asia/Kolkata}") String timeZone) {
        this.closingRepository = closingRepository;
        this.productRepository = productRepository;
        this.customerLedgerRepository = customerLedgerRepository;
        this.currentUser = currentUser;
        this.posAggregation = posAggregation;
        this.businessZone = ZoneId.of(timeZone);
    }

    @Transactional
    public ClosingResponse save(ClosingRequest request) {
        UUID shopId = currentUser.shopId();
        DailyClosing closing = closingRepository.findByShopIdAndBusinessDate(shopId, request.businessDate())
                .orElseGet(() -> {
                    DailyClosing created = new DailyClosing();
                    created.setShop(currentUser.requireUser().getShop());
                    created.setBusinessDate(request.businessDate());
                    return created;
                });
        if (closing.getStatus() == ClosingStatus.CLOSED) {
            throw new IllegalStateException("This daily closing is already closed");
        }

        replaceItems(closing, request.items(), shopId);
        replaceExpenses(closing, request.expenses());
        closing.setCash(money(request.cash(), "cash"));
        closing.setUpi(money(request.upi(), "upi"));
        closing.setUdhar(money(request.udhar(), "udhar"));
        closing.setPosAutoAggregated(false);
        closing.setPosSaleCount(0L);
        closing.setPosSalesTotal(ZERO);
        closing.setPosCash(ZERO);
        closing.setPosUpi(ZERO);
        closing.setPosUdhar(ZERO);
        closing.setExpectedSales(expectedFromItems(closing));
        closing.setNotes(request.notes() == null ? null : request.notes().trim());
        calculateCollectionAndProfit(closing, request.businessDate(), shopId);
        return ClosingResponse.from(closingRepository.save(closing));
    }

    @Transactional(readOnly = true)
    public List<ClosingResponse> list() {
        return closingRepository.findTop30ByShopIdOrderByBusinessDateDesc(currentUser.shopId())
                .stream().map(ClosingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClosingResponse get(UUID id) {
        return ClosingResponse.from(find(id));
    }

    @Transactional
    public ClosingResponse close(UUID id) {
        DailyClosing closing = find(id);
        if (closing.getStatus() == ClosingStatus.CLOSED) {
            return ClosingResponse.from(closing);
        }
        syncPosIfAvailable(closing);
        if (!closing.getPosAutoAggregated() && closing.getItems().isEmpty()) {
            throw new IllegalStateException("Add at least one item before closing the day");
        }
        closing.setStatus(ClosingStatus.CLOSED);
        closing.setClosedAt(Instant.now());
        return ClosingResponse.from(closingRepository.save(closing));
    }

    @Transactional
    public ClosingResponse syncPos(UUID id) {
        DailyClosing closing = find(id);
        if (closing.getStatus() == ClosingStatus.CLOSED) {
            throw new IllegalStateException("Closed days cannot be changed");
        }
        syncPosIfAvailable(closing);
        return ClosingResponse.from(closingRepository.save(closing));
    }

    private void syncPosIfAvailable(DailyClosing closing) {
        PosClosingAggregationService.PosTotals totals = posAggregation.aggregate(closing.getBusinessDate());
        UUID shopId = currentUser.shopId();
        if (totals.saleCount() > 0) {
            closing.setExpectedSales(totals.total());
            closing.setPosAutoAggregated(true);
            closing.setPosSaleCount(totals.saleCount());
            closing.setPosSalesTotal(totals.total());
            closing.setPosCash(totals.cash());
            closing.setPosUpi(totals.upi());
            closing.setPosUdhar(totals.udhar());
        } else {
            closing.setPosAutoAggregated(false);
            closing.setPosSaleCount(0L);
            closing.setPosSalesTotal(ZERO);
            closing.setPosCash(ZERO);
            closing.setPosUpi(ZERO);
            closing.setPosUdhar(ZERO);
            closing.setExpectedSales(expectedFromItems(closing));
        }
        calculateCollectionAndProfit(closing, closing.getBusinessDate(), shopId);
    }

    private void calculateCollectionAndProfit(DailyClosing closing, LocalDate businessDate, UUID shopId) {
        Repayments repayments = repayments(shopId, businessDate);
        closing.setCustomerRepaymentCash(repayments.cash());
        closing.setCustomerRepaymentUpi(repayments.upi());

        BigDecimal actual = closing.getCash().add(closing.getUpi()).add(closing.getUdhar())
                .add(repayments.cash()).add(repayments.upi()).setScale(2, RoundingMode.HALF_UP);
        closing.setActualCollection(actual);
        closing.setVariance(closing.getExpectedSales().subtract(actual).setScale(2, RoundingMode.HALF_UP));

        BigDecimal expenses = closing.getExpenses().stream().map(ClosingExpense::getAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        closing.setExpensesTotal(expenses);
        closing.setNetProfit(closing.getExpectedSales().subtract(expenses).setScale(2, RoundingMode.HALF_UP));
    }

    private Repayments repayments(UUID shopId, LocalDate businessDate) {
        Instant from = businessDate.atStartOfDay(businessZone).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(businessZone).toInstant();
        BigDecimal cash = ZERO;
        BigDecimal upi = ZERO;
        for (Object[] row : customerLedgerRepository.sumRepaymentsByShopAndCreatedAt(shopId, from, to)) {
            PaymentMethod method = (PaymentMethod) row[0];
            BigDecimal amount = ((BigDecimal) row[1]).setScale(2, RoundingMode.HALF_UP);
            if (method == PaymentMethod.CASH) cash = amount;
            if (method == PaymentMethod.UPI) upi = amount;
        }
        return new Repayments(cash, upi);
    }

    private BigDecimal expectedFromItems(DailyClosing closing) {
        return closing.getItems().stream().map(DailyClosingItem::getAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private void replaceItems(DailyClosing closing, List<ClosingItemRequest> requests, UUID shopId) {
        closing.getItems().clear();
        for (ClosingItemRequest request : requests) {
            Product product = productRepository.findByIdAndShopId(request.productId(), shopId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + request.productId()));
            DailyClosingItem item = new DailyClosingItem();
            item.setClosing(closing);
            item.setProduct(product);
            item.setProductNameSnapshot(product.getName());
            item.setUnitPriceSnapshot(product.getSellingPrice());
            item.setQuantity(request.quantity());
            item.setAmount(product.getSellingPrice().multiply(request.quantity()).setScale(2, RoundingMode.HALF_UP));
            closing.getItems().add(item);
        }
    }

    private void replaceExpenses(DailyClosing closing, List<ClosingExpenseRequest> requests) {
        closing.getExpenses().clear();
        if (requests == null) return;
        for (ClosingExpenseRequest request : requests) {
            ClosingExpense expense = new ClosingExpense();
            expense.setClosing(closing);
            expense.setDescription(request.description().trim());
            expense.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
            closing.getExpenses().add(expense);
        }
    }

    private BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(field + " cannot be negative");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private DailyClosing find(UUID id) {
        return closingRepository.findByIdAndShopId(id, currentUser.shopId())
                .orElseThrow(() -> new IllegalArgumentException("Daily closing not found"));
    }

    private record Repayments(BigDecimal cash, BigDecimal upi) {}
}
