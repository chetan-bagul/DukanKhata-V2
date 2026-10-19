package com.dukankhata.customer;

import com.dukankhata.common.exception.ConflictException;
import com.dukankhata.common.exception.ResourceNotFoundException;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.customer.dto.*;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.pos.PaymentMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal; import java.util.*;

@Service
public class CustomerService {
    private final CustomerRepository customers; private final CustomerLedgerRepository ledger; private final ShopRepository shops; private final CurrentUserService current; private final com.dukankhata.notification.automation.AutomatedNotificationService automatedNotifications;
    public CustomerService(CustomerRepository customers,CustomerLedgerRepository ledger,ShopRepository shops,CurrentUserService current,com.dukankhata.notification.automation.AutomatedNotificationService automatedNotifications){this.customers=customers;this.ledger=ledger;this.shops=shops;this.current=current;this.automatedNotifications=automatedNotifications;}

    @Transactional(readOnly=true) public List<CustomerResponse> list(){UUID shopId=current.shopId();return customers.findAllByShopIdAndActiveTrueOrderByCustomerNumberAsc(shopId).stream().map(CustomerResponse::from).toList();}

    @Transactional public CustomerResponse create(CustomerRequest request){
        UUID shopId=current.shopId(); String name=request.name().trim();
        if(customers.existsByShopIdAndNameIgnoreCaseAndActiveTrue(shopId,name)) throw new ConflictException("A customer with this name already exists");
        Shop shop=shops.findByIdForUpdate(shopId).orElseThrow(()->new ResourceNotFoundException("Shop not found"));
        int number=shop.getNextCustomerNumber(); shop.setNextCustomerNumber(number+1); shops.save(shop);
        Customer c=new Customer(); c.setShop(shop); c.setCustomerNumber(number); c.setName(name); c.setMobile(normalize(request.mobile())); c.setAddress(blankToNull(request.address())); c.setGstin(blankToNull(request.gstin())); c.setStateCode(blankToNull(request.stateCode()));
        return CustomerResponse.from(customers.save(c));
    }

    @Transactional public CustomerResponse update(UUID id,CustomerRequest request){
        UUID shopId=current.shopId(); Customer c=customers.findByIdAndShopIdAndActiveTrue(id,shopId).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
        c.setName(request.name().trim()); c.setMobile(normalize(request.mobile())); c.setAddress(blankToNull(request.address())); c.setGstin(blankToNull(request.gstin())); c.setStateCode(blankToNull(request.stateCode())); return CustomerResponse.from(customers.save(c));
    }

    @Transactional public void deactivate(UUID id){Customer c=customers.findByIdAndShopIdAndActiveTrue(id,current.shopId()).orElseThrow(()->new ResourceNotFoundException("Customer not found")); c.setActive(false); customers.save(c);}

    @Transactional(readOnly=true) public CustomerResponse get(UUID id){return CustomerResponse.from(customers.findByIdAndShopIdAndActiveTrue(id,current.shopId()).orElseThrow(()->new ResourceNotFoundException("Customer not found")));}

    @Transactional public LedgerEntryResponse charge(UUID id,LedgerEntryRequest request){return addEntry(id,LedgerEntryType.CHARGE,request.amount(),request.description(),null,null,null);}
    @Transactional public LedgerEntryResponse payment(UUID id,LedgerEntryRequest request){
        if(request.paymentMethod()!=PaymentMethod.CASH && request.paymentMethod()!=PaymentMethod.UPI) {
            throw new IllegalArgumentException("Customer repayment method must be CASH or UPI");
        }
        return addEntry(id,LedgerEntryType.PAYMENT,request.amount(),request.description(),null,null,request.paymentMethod());
    }
    @Transactional public LedgerEntryResponse creditRefund(UUID id,BigDecimal amount,String description){
        return addEntry(id,LedgerEntryType.PAYMENT,amount,description,null,null,null);
    }

    @Transactional(readOnly=true) public List<LedgerEntryResponse> ledger(UUID id){
        UUID shopId=current.shopId(); customers.findByIdAndShopIdAndActiveTrue(id,shopId).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
        return this.ledger.findAllByCustomerIdAndShopIdOrderByCreatedAtDesc(id,shopId).stream().map(LedgerEntryResponse::from).toList();
    }

    private LedgerEntryResponse addEntry(UUID id,LedgerEntryType type,BigDecimal amount,String description,String refType,UUID refId,PaymentMethod paymentMethod){
        UUID shopId=current.shopId(); Customer c=customers.findByIdAndShopIdForUpdate(id,shopId).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
        BigDecimal delta=type==LedgerEntryType.CHARGE?amount:amount; BigDecimal newBalance;
        if(type==LedgerEntryType.CHARGE) newBalance=c.getBalance().subtract(delta); else if(type==LedgerEntryType.PAYMENT) newBalance=c.getBalance().add(delta); else newBalance=c.getBalance().add(delta);
        c.setBalance(newBalance); customers.save(c);
        CustomerLedgerEntry e=new CustomerLedgerEntry(); e.setShopId(shopId); e.setCustomer(c); e.setType(type); e.setPaymentMethod(paymentMethod); e.setAmount(amount); e.setBalanceAfter(newBalance); e.setDescription(blankToNull(description)); e.setReferenceType(refType); e.setReferenceId(refId);
        LedgerEntryResponse response=LedgerEntryResponse.from(ledger.save(e));
        if(type==LedgerEntryType.PAYMENT) automatedNotifications.customerPayment(c.getShop(), c, amount);
        else if(type==LedgerEntryType.CHARGE) automatedNotifications.udharReminder(c.getShop(), c);
        return response;
    }
    private String normalize(String mobile){if(mobile==null||mobile.isBlank())return null;return mobile.trim();} private String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
}
