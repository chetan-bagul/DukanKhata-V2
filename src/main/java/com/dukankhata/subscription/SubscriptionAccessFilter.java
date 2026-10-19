package com.dukankhata.subscription;

import com.dukankhata.auth.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class SubscriptionAccessFilter extends OncePerRequestFilter {
 private final SubscriptionRepository subscriptions; private final int trialDays;
 public SubscriptionAccessFilter(SubscriptionRepository subscriptions,@Value("${subscription.trial-days:30}") int trialDays){this.subscriptions=subscriptions;this.trialDays=trialDays;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String path=req.getRequestURI();
  if(path.startsWith("/api/v1/auth/")||path.startsWith("/api/v1/subscription/")||path.equals("/api/v1/health")||path.equals("/actuator/health")){chain.doFilter(req,res);return;}
  Authentication a=SecurityContextHolder.getContext().getAuthentication();
  if(a==null||!(a.getPrincipal() instanceof User)){chain.doFilter(req,res);return;}
  User user=(User)a.getPrincipal(); Instant now=Instant.now();
  var active=subscriptions.findTopByShopIdAndStatusAndEndsAtAfterOrderByCreatedAtDescIdDesc(user.getShop().getId(),SubscriptionStatus.ACTIVE,now);
  boolean allowed=active.isPresent()&&active.get().getPlan().supportsOperatingMode(user.getShop().getOperatingMode());
  if(!allowed){boolean paidBefore=subscriptions.existsByShopIdAndRazorpayPaymentIdIsNotNull(user.getShop().getId());allowed=!paidBefore&&now.isBefore(user.getShop().getCreatedAt().plus(trialDays,ChronoUnit.DAYS));}
  if(!allowed){res.setStatus(402);res.setContentType("application/json");res.getWriter().write("{\"success\":false,\"status\":402,\"code\":\"SUBSCRIPTION_REQUIRED\",\"message\":\"Your trial or subscription has expired. Please choose a plan to continue.\"}");return;}
  chain.doFilter(req,res);
 }
}
