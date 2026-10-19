package com.dukankhata.pos;

import com.dukankhata.common.exception.*;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.customer.*;
import com.dukankhata.customer.dto.LedgerEntryRequest;
import com.dukankhata.product.*;
import com.dukankhata.staff.*;
import com.dukankhata.shop.*;
import com.dukankhata.pos.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SaleService {
 private final SaleRepository sales; private final ProductRepository products; private final CustomerRepository customers; private final CurrentUserService current; private final ShopRepository shops; private final CustomerService customerService; private final com.dukankhata.inventory.InventoryService inventory; private final StaffRepository staff; private final StaffService staffService; private final com.dukankhata.notification.automation.AutomatedNotificationService automatedNotifications;
 public SaleService(SaleRepository sales,ProductRepository products,CustomerRepository customers,CurrentUserService current,ShopRepository shops,CustomerService customerService,com.dukankhata.inventory.InventoryService inventory,StaffRepository staff,StaffService staffService,com.dukankhata.notification.automation.AutomatedNotificationService automatedNotifications){this.sales=sales;this.products=products;this.customers=customers;this.current=current;this.shops=shops;this.customerService=customerService;this.inventory=inventory;this.staff=staff;this.staffService=staffService;this.automatedNotifications=automatedNotifications;}
 @Transactional public SaleResponse create(CreateSaleRequest r){
  UUID shopId=current.shopId(); Shop shop=shops.findByIdForUpdate(shopId).orElseThrow(()->new ResourceNotFoundException("Shop not found"));
  Staff seller=null; if(r.sellerId()!=null) seller=staff.findByIdAndShopIdAndActiveTrue(r.sellerId(),shopId).orElseThrow(()->new ResourceNotFoundException("Staff seller not found"));
  Customer customer=null; if(r.customerId()!=null) customer=customers.findByIdAndShopIdAndActiveTrue(r.customerId(),shopId).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
  Sale sale=new Sale(); sale.setShop(shop); sale.setCustomer(customer); sale.setSeller(seller); sale.setInvoiceNumber(nextInvoice(shopId,LocalDate.now(ZoneId.of("Asia/Kolkata"))));
  BigDecimal subtotal=BigDecimal.ZERO, taxable=BigDecimal.ZERO, cgst=BigDecimal.ZERO, sgst=BigDecimal.ZERO, igst=BigDecimal.ZERO;
  boolean interstate = customer != null && customer.getStateCode()!=null && shop.getStateCode()!=null && !shop.getStateCode().equals(customer.getStateCode());
  for(var req:r.items()){
   Product product=null; String name=req.itemName().trim(); BigDecimal price=req.unitPrice(); BigDecimal gstRate=req.gstRate()==null?BigDecimal.ZERO:req.gstRate(); String hsn=req.hsnCode();
   if(req.productId()!=null){product=products.findByIdAndShopIdAndActiveTrue(req.productId(),shopId).orElseThrow(()->new ResourceNotFoundException("Product not found")); name=product.getName(); price=product.getSellingPrice(); gstRate=product.getGstRate(); hsn=product.getHsnCode();}
   BigDecimal line=price.multiply(req.quantity()).setScale(2,RoundingMode.HALF_UP); BigDecimal itemTax=line.multiply(gstRate).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP);
   BigDecimal itemCgst=BigDecimal.ZERO,itemSgst=BigDecimal.ZERO,itemIgst=BigDecimal.ZERO;
   if(interstate) itemIgst=itemTax; else { itemCgst=itemTax.divide(BigDecimal.valueOf(2),2,RoundingMode.HALF_UP); itemSgst=itemTax.subtract(itemCgst); }
   subtotal=subtotal.add(line); taxable=taxable.add(line); cgst=cgst.add(itemCgst); sgst=sgst.add(itemSgst); igst=igst.add(itemIgst);
   SaleItem i=new SaleItem(); i.setSale(sale); i.setProduct(product); i.setItemName(name); i.setUnitPrice(price); i.setQuantity(req.quantity()); i.setCostPrice(product==null?BigDecimal.ZERO.setScale(2):product.getAverageCostPrice().setScale(2,RoundingMode.HALF_UP)); i.setLineTotal(line.add(itemTax)); i.setHsnCode(hsn); i.setGstRate(gstRate); i.setTaxableAmount(line); i.setCgstAmount(itemCgst); i.setSgstAmount(itemSgst); i.setIgstAmount(itemIgst); sale.getItems().add(i);
  }
  BigDecimal tax=cgst.add(sgst).add(igst), total=subtotal.add(tax).setScale(2,RoundingMode.HALF_UP);
  sale.setSubtotal(subtotal); sale.setTaxableAmount(taxable); sale.setTax(tax); sale.setCgstAmount(cgst); sale.setSgstAmount(sgst); sale.setIgstAmount(igst); sale.setTotal(total);
  BigDecimal commissionRate=seller==null?BigDecimal.ZERO:seller.getCommissionRate(); BigDecimal commission=total.multiply(commissionRate).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP); sale.setSellerCommissionRate(commissionRate); sale.setSellerCommissionAmount(commission);
  BigDecimal paid=BigDecimal.ZERO; boolean hasUdhar=false;
  for(var pr:r.payments()){if(pr.method()==PaymentMethod.UDHAR){hasUdhar=true;if(customer==null) throw new BusinessException("Customer is required for Udhar payment");} paid=paid.add(pr.amount()); SalePayment p=new SalePayment();p.setSale(sale);p.setMethod(pr.method());p.setAmount(pr.amount());sale.getPayments().add(p);}
  if(paid.compareTo(total)!=0) throw new BusinessException("Payment total must exactly match invoice total");
  for(var item:sale.getItems()) if(item.getProduct()!=null) inventory.deductForSale(item.getProduct(),item.getQuantity(),sale.getInvoiceNumber());
  Sale saved=sales.save(sale);
  if(hasUdhar) for(var pr:r.payments()) if(pr.method()==PaymentMethod.UDHAR) customerService.charge(customer.getId(),new LedgerEntryRequest(pr.amount(),"POS invoice "+saved.getInvoiceNumber()));
  if(seller!=null&&commission.signum()>0) staffService.earn(seller.getId(),commission,saved.getId(),saved.getInvoiceNumber());
  automatedNotifications.invoice(shop, customer, saved.getInvoiceNumber(), saved.getTotal());
  for (var item : saved.getItems()) if (item.getProduct()!=null && item.getProduct().getCurrentStock().compareTo(item.getProduct().getReorderLevel())<=0) automatedNotifications.lowStock(shop, item.getProduct());
  return SaleResponse.from(saved);
 }
 @Transactional(readOnly=true) public SaleResponse get(UUID id){return SaleResponse.from(sales.findByIdAndShopId(id,current.shopId()).orElseThrow(()->new ResourceNotFoundException("Sale not found")));}
 @Transactional(readOnly=true) public org.springframework.data.domain.Slice<SaleResponse> list(int page,int size){var pageable=org.springframework.data.domain.PageRequest.of(page,size);return sales.findAllByShopIdOrderByCreatedAtDescIdDesc(current.shopId(),pageable).map(SaleResponse::from);}
 private String nextInvoice(UUID shopId, LocalDate today){
  String prefix = "INV-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
  Integer maxSuffix = sales.findMaxInvoiceSuffix(shopId,prefix,prefix.length()+1);
  return prefix + String.format("%04d", Math.addExact(maxSuffix == null ? 0 : maxSuffix, 1));
 }
}
