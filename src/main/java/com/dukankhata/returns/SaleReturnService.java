package com.dukankhata.returns;
import com.dukankhata.common.exception.*; import com.dukankhata.common.security.CurrentUserService; import com.dukankhata.customer.*; import com.dukankhata.customer.dto.LedgerEntryRequest; import com.dukankhata.inventory.InventoryService; import com.dukankhata.pos.*; import com.dukankhata.returns.dto.*; import com.dukankhata.staff.StaffService; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.math.*; import java.util.*;
@Service public class SaleReturnService {
 private final SaleRepository sales; private final SaleReturnRepository returns; private final ReturnRefundRepository refunds; private final CurrentUserService current; private final InventoryService inventory; private final CustomerService customers; private final StaffService staff;
 public SaleReturnService(SaleRepository s,SaleReturnRepository r,ReturnRefundRepository f,CurrentUserService c,InventoryService i,CustomerService cs,StaffService st){sales=s;returns=r;refunds=f;current=c;inventory=i;customers=cs;staff=st;}
 @Transactional public ReturnResponse create(UUID saleId,CreateReturnRequest req){
  UUID shop=current.shopId(); Sale sale=sales.findByIdAndShopId(saleId,shop).orElseThrow(()->new ResourceNotFoundException("Sale not found"));
  if(sale.getStatus()!=SaleStatus.COMPLETED) throw new BusinessException("Only completed sales can be returned");
  if(returns.findBySaleIdAndShopId(saleId,shop).isPresent()) throw new ConflictException("This sale has already been returned");
  BigDecimal total=BigDecimal.ZERO; for(var x:req.refunds()) total=total.add(x.amount()); total=total.setScale(2,RoundingMode.HALF_UP);
  if(total.compareTo(sale.getTotal())!=0) throw new BusinessException("Refund total must exactly match invoice total: ₹"+sale.getTotal());
  boolean udhar=false; for(var x:req.refunds()) if(x.method()==ReturnRefundMethod.UDHAR) udhar=true;
  if(udhar && sale.getCustomer()==null) throw new BusinessException("This sale has no customer, so Udhar refund is not available");
  SaleReturn ret=new SaleReturn(); ret.setShop(sale.getShop()); ret.setSale(sale); ret.setRefundTotal(total); ret.setReason(req.reason().trim()); ret=returns.save(ret);
  for(var item:sale.getItems()) if(item.getProduct()!=null) inventory.restoreForReturn(item.getProduct(),item.getQuantity(),sale.getInvoiceNumber());
  if(udhar) for(var x:req.refunds()) if(x.method()==ReturnRefundMethod.UDHAR) customers.creditRefund(sale.getCustomer().getId(),x.amount(),"Refund for POS invoice "+sale.getInvoiceNumber());
  if(sale.getSeller()!=null && sale.getSellerCommissionAmount().signum()>0) staff.adjustment(sale.getSeller().getId(),new com.dukankhata.staff.dto.StaffAdjustmentRequest(sale.getSellerCommissionAmount().negate(),"Commission reversed for returned invoice "+sale.getInvoiceNumber()));
  for(var x:req.refunds()){ReturnRefund rr=new ReturnRefund();rr.setSaleReturn(ret);rr.setMethod(x.method());rr.setAmount(x.amount().setScale(2,RoundingMode.HALF_UP));refunds.save(rr);}
  sale.setStatus(SaleStatus.RETURNED); sales.save(sale); return ReturnResponse.from(ret,refunds.findBySaleReturnId(ret.getId()));
 }
 @Transactional(readOnly=true) public List<ReturnResponse> list(){UUID shop=current.shopId(); return returns.findTop100ByShopIdOrderByCreatedAtDesc(shop).stream().map(r->ReturnResponse.from(r,refunds.findBySaleReturnId(r.getId()))).toList();}
}
