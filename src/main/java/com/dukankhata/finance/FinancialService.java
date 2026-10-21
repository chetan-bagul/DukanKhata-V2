package com.dukankhata.finance;
import com.dukankhata.closing.*; import com.dukankhata.common.security.CurrentUserService; import com.dukankhata.common.time.BusinessTime; import com.dukankhata.finance.dto.*; import com.dukankhata.pos.*; import com.dukankhata.shop.Shop; import com.dukankhata.staff.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.math.*; import java.time.*; import java.util.*;
@Service public class FinancialService {
 private static final BigDecimal ZERO=BigDecimal.ZERO.setScale(2);
 private final FinancialExpenseRepository expenses; private final SaleRepository sales; private final DailyClosingRepository closings; private final StaffLedgerRepository staffLedger; private final CurrentUserService current; private final BusinessTime businessTime;
 public FinancialService(FinancialExpenseRepository e,SaleRepository s,DailyClosingRepository c,StaffLedgerRepository sl,CurrentUserService u,BusinessTime businessTime){expenses=e;sales=s;closings=c;staffLedger=sl;current=u;this.businessTime=businessTime;}
 @Transactional public ExpenseResponse addExpense(ExpenseRequest r){FinancialExpense e=new FinancialExpense();e.setShop(current.requireUser().getShop());e.setExpenseDate(r.expenseDate());e.setCategory(r.category());e.setDescription(r.description().trim());e.setAmount(money(r.amount()));e.setRecurringMonthly(r.recurringMonthly());return ExpenseResponse.from(expenses.save(e));}
 @Transactional(readOnly=true) public List<ExpenseResponse> listExpenses(LocalDate from,LocalDate to){validate(from,to);return expenses.findByShopIdAndExpenseDateBetweenOrderByExpenseDateDescCreatedAtDesc(current.shopId(),from,to).stream().map(ExpenseResponse::from).toList();}
 @Transactional public void deleteExpense(UUID id){FinancialExpense e=expenses.findByIdAndShopId(id,current.shopId()).orElseThrow(()->new IllegalArgumentException("Expense not found"));expenses.delete(e);}
 @Transactional(readOnly=true) public FinancialSummaryResponse summary(LocalDate from,LocalDate to){validate(from,to);UUID shopId=current.shopId();var range=businessTime.range(from,to);Instant start=range.fromInclusive();Instant end=range.toExclusive();
  BigDecimal posRevenue=sales.sumCompletedTotal(shopId,start,end);BigDecimal cogs=sales.sumCompletedCostOfGoods(shopId,start,end);long saleCount=sales.countCompletedByShopIdAndCreatedAtBetween(shopId,start,end);
  Object[] closingTotals=closings.summarizeByShopAndDateRange(shopId,from,to,ClosingStatus.CLOSED).getFirst();BigDecimal closingRevenue=(BigDecimal)closingTotals[0];BigDecimal closingExpenses=(BigDecimal)closingTotals[1];
  BigDecimal revenue=posRevenue.add(closingRevenue); BigDecimal gross=revenue.subtract(cogs); BigDecimal commissionExpenses=staffLedger.findCommissionEarnedByShopIdBetween(shopId,start,end,StaffLedgerType.COMMISSION_EARNED); BigDecimal other=expenses.sumByShopAndDateRange(shopId,from,to); BigDecimal totalExp=closingExpenses.add(other).add(commissionExpenses); BigDecimal net=gross.subtract(totalExp); BigDecimal gm=pct(gross,revenue), nm=pct(net,revenue);
  return new FinancialSummaryResponse(from,to,saleCount,money(revenue),money(cogs),money(gross),gm,money(closingExpenses),money(other),money(commissionExpenses),money(totalExp),money(net),nm);
 }
 private BigDecimal pct(BigDecimal value,BigDecimal base){if(base.signum()==0)return ZERO;return value.multiply(BigDecimal.valueOf(100)).divide(base,2,RoundingMode.HALF_UP);}
 private BigDecimal money(BigDecimal x){return x.setScale(2,RoundingMode.HALF_UP);} private void validate(LocalDate f,LocalDate t){if(f==null||t==null)throw new IllegalArgumentException("Both from and to dates are required");if(f.isAfter(t))throw new IllegalArgumentException("From date cannot be after to date");if(f.plusMonths(12).isBefore(t))throw new IllegalArgumentException("Date range cannot exceed 12 months");}
}
