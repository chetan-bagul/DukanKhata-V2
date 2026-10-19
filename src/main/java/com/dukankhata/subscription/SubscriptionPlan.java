package com.dukankhata.subscription;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name="subscription_plans", uniqueConstraints=@UniqueConstraint(name="uk_plan_code", columnNames="code"))
public class SubscriptionPlan {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,length=40) private String code;
 @Column(nullable=false,length=100) private String name;
 @Column(nullable=false,precision=12,scale=2) private BigDecimal amount;
 @Column(nullable=false,length=3) private String currency="INR";
 @Column(nullable=false) private Integer durationDays;
 @Column(nullable=false) private boolean active=true;
 public UUID getId(){return id;} public String getCode(){return code;} public String getName(){return name;} public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;} public Integer getDurationDays(){return durationDays;} public boolean isActive(){return active;}
 public boolean supportsOperatingMode(String operatingMode){String mode=operatingMode==null?"":operatingMode.trim().toUpperCase();if("BOTH".equals(mode))return true;if(code==null)return false;String planMode=code.startsWith("SMALL_")?"SMALL":code.startsWith("LARGE_")?"LARGE":null;return planMode!=null&&mode.startsWith(planMode);}
 public void setCode(String v){code=v;} public void setName(String v){name=v;} public void setAmount(BigDecimal v){amount=v;} public void setCurrency(String v){currency=v;} public void setDurationDays(Integer v){durationDays=v;} public void setActive(boolean v){active=v;}
}
