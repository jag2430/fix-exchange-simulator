package com.example.exchange.liquidity;

import com.example.exchange.engine.MatchingEngine;
import com.example.exchange.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Liquidity Provider - Simulates a market maker by posting bid/ask quotes
 * to the order book around the current market price.
 *
 * Now with DYNAMIC PROFILES - spreads and quantities are automatically
 * adjusted based on each stock's market cap tier.
 */
@Slf4j
@Component
@EnableScheduling
public class LiquidityProvider {

  private static final String MM_SENDER_COMP_ID = "MARKET_MAKER";
  private static final String MM_TARGET_COMP_ID = "EXCHANGE";

  private final MatchingEngine matchingEngine;
  private final FinnhubPriceService priceService;
  private final LiquidityProfileService profileService;

  // Symbols with active MM quotes
  private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();

  // Track the reference price used for each symbol's quotes
  private final Map<String, BigDecimal> symbolPrices = new ConcurrentHashMap<>();

  // Counter for generating unique MM order IDs
  private final AtomicLong mmOrderCounter = new AtomicLong(1);

  @Value("${liquidity-provider.enabled:true}")
  private boolean enabled;

  @Value("${liquidity-provider.fallback-price:100.00}")
  private BigDecimal fallbackPrice;

  public LiquidityProvider(MatchingEngine matchingEngine,
                           FinnhubPriceService priceService,
                           LiquidityProfileService profileService) {
    this.matchingEngine = matchingEngine;
    this.priceService = priceService;
    this.profileService = profileService;
  }

  @PostConstruct
  public void init() {
    if (enabled) {
      log.info("=================================================");
      log.info("LIQUIDITY PROVIDER ENABLED (Dynamic Profiles)");
      log.info("  Profiles auto-adjust based on market cap:");
      log.info("    MEGA_CAP  (>$500B): 1 bps spread, 1000 base qty");
      log.info("    LARGE_CAP ($50-500B): 2 bps spread, 500 base qty");
      log.info("    MID_CAP   ($10-50B): 5 bps spread, 200 base qty");
      log.info("    SMALL_CAP (<$10B): 10 bps spread, 100 base qty");
      log.info("=================================================");
    } else {
      log.info("Liquidity Provider DISABLED");
    }
  }

  /**
   * Ensure liquidity exists for a symbol BEFORE matching an incoming order.
   */
  public void ensureLiquidity(String symbol, Order incomingOrder) {
    if (!enabled) {
      return;
    }

    String upperSymbol = symbol.toUpperCase();

    // Fast path: quotes already exist for this symbol
    if (activeSymbols.contains(upperSymbol)) {
      log.debug("Liquidity already exists for {}", upperSymbol);
      return;
    }

    log.info("First order for {} - setting up liquidity...", upperSymbol);
    long startTime = System.currentTimeMillis();

    // Get the liquidity profile for this stock (fetches market cap)
    LiquidityProfile profile = profileService.getProfile(upperSymbol);

    // Get reference price
    BigDecimal referencePrice = getReferencePriceForSymbol(upperSymbol, incomingOrder);

    // Post quotes using the profile's parameters
    postQuotes(upperSymbol, referencePrice, profile);

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("Liquidity setup for {} complete in {}ms - {} ({}) @ ${}",
        upperSymbol, elapsed, profile.getTier(),
        profile.getFormattedMarketCap(), referencePrice);
  }

  /**
   * Get reference price for a symbol.
   */
  private BigDecimal getReferencePriceForSymbol(String symbol, Order incomingOrder) {
    // Try Finnhub first
    BigDecimal finnhubPrice = priceService.getPrice(symbol);
    if (finnhubPrice != null) {
      return finnhubPrice;
    }

    // Fallback to order's limit price if available
    if (incomingOrder != null &&
        incomingOrder.getOrderType() == Order.OrderType.LIMIT &&
        incomingOrder.getPrice() != null &&
        incomingOrder.getPrice().compareTo(BigDecimal.ZERO) > 0) {

      log.warn("Using order limit price as reference for {}: ${}",
          symbol, incomingOrder.getPrice());
      return incomingOrder.getPrice();
    }

    // Last resort: fallback price
    log.warn("Using fallback price for {}: ${}", symbol, fallbackPrice);
    return fallbackPrice;
  }

  /**
   * Post bid and ask quotes using the stock's profile parameters.
   */
  private void postQuotes(String symbol, BigDecimal referencePrice, LiquidityProfile profile) {
    symbolPrices.put(symbol, referencePrice);

    int levels = profile.getLevels();
    int baseSpreadBps = profile.getBaseSpreadBps();
    int levelIncrementBps = profile.getLevelIncrementBps();
    int baseQuantity = profile.getBaseQuantity();
    int quantityMultiplier = profile.getQuantityMultiplier();

    // Post multiple levels of bids and asks
    for (int level = 0; level < levels; level++) {
      // Calculate offset in basis points
      int offsetBps = baseSpreadBps + (level * levelIncrementBps);
      BigDecimal offsetPercent = BigDecimal.valueOf(offsetBps).divide(
          BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);

      // Calculate bid and ask prices
      BigDecimal bidPrice = referencePrice.multiply(
          BigDecimal.ONE.subtract(offsetPercent)).setScale(2, RoundingMode.DOWN);
      BigDecimal askPrice = referencePrice.multiply(
          BigDecimal.ONE.add(offsetPercent)).setScale(2, RoundingMode.UP);

      // Calculate quantity (more size at worse prices)
      int quantity = baseQuantity * (int) Math.pow(quantityMultiplier, level);

      // Post bid order
      postMMOrder(symbol, Order.Side.BUY, bidPrice, quantity, level);

      // Post ask order
      postMMOrder(symbol, Order.Side.SELL, askPrice, quantity, level);
    }

    activeSymbols.add(symbol);

    log.info("Posted {} levels for {} [{}]: spread={} bps, baseQty={}",
        levels, symbol, profile.getTier(), baseSpreadBps, baseQuantity);
  }

  /**
   * Post a single market maker order.
   */
  private void postMMOrder(String symbol, Order.Side side, BigDecimal price, int quantity, int level) {
    String clOrdId = String.format("MM-%s-%s-%d-%d",
        symbol, side, level, mmOrderCounter.getAndIncrement());

    Order mmOrder = Order.builder()
        .clOrdId(clOrdId)
        .symbol(symbol)
        .side(side)
        .orderType(Order.OrderType.LIMIT)
        .price(price)
        .quantity(quantity)
        .senderCompId(MM_SENDER_COMP_ID)
        .targetCompId(MM_TARGET_COMP_ID)
        .build();

    matchingEngine.submitOrder(mmOrder);

    log.debug("Posted MM order: {} {} {} @ ${} (level {})",
        side, quantity, symbol, price, level);
  }

  /**
   * Refresh quotes for all active symbols.
   */
  @Scheduled(fixedRateString = "${liquidity-provider.refresh-interval-ms:5000}")
  public void refreshAllQuotes() {
    if (!enabled || activeSymbols.isEmpty()) {
      return;
    }

    log.debug("Refreshing quotes for {} symbols...", activeSymbols.size());

    for (String symbol : activeSymbols) {
      try {
        refreshQuotesForSymbol(symbol);
      } catch (Exception e) {
        log.error("Failed to refresh quotes for {}: {}", symbol, e.getMessage());
      }
    }
  }

  /**
   * Refresh quotes for a single symbol.
   */
  private void refreshQuotesForSymbol(String symbol) {
    BigDecimal newPrice = priceService.getPrice(symbol);
    if (newPrice == null) {
      return;
    }

    BigDecimal oldPrice = symbolPrices.get(symbol);
    if (oldPrice != null && newPrice.compareTo(oldPrice) == 0) {
      return;
    }

    log.info("Refreshing quotes for {} - price moved from ${} to ${}",
        symbol, oldPrice, newPrice);

    // Get the profile (should be cached now)
    LiquidityProfile profile = profileService.getProfile(symbol);

    // Post fresh quotes with profile parameters
    postQuotes(symbol, newPrice, profile);
  }

  /**
   * Manually trigger liquidity setup for a symbol.
   */
  public void setupLiquidity(String symbol) {
    if (!enabled) {
      log.warn("Cannot setup liquidity - provider is disabled");
      return;
    }
    ensureLiquidity(symbol.toUpperCase(), null);
  }

  /**
   * Get status information for monitoring.
   */
  public Map<String, Object> getStatus() {
    Map<String, Object> status = new HashMap<>();
    status.put("enabled", enabled);
    status.put("activeSymbols", new ArrayList<>(activeSymbols));
    status.put("symbolPrices", new HashMap<>(symbolPrices));

    // Include profile info for each active symbol
    Map<String, Map<String, Object>> profiles = new HashMap<>();
    for (String symbol : activeSymbols) {
      LiquidityProfile profile = profileService.getCachedProfile(symbol);
      if (profile != null) {
        profiles.put(symbol, Map.of(
            "tier", profile.getTier().name(),
            "marketCap", profile.getFormattedMarketCap(),
            "spreadBps", profile.getBaseSpreadBps(),
            "baseQuantity", profile.getBaseQuantity()
        ));
      }
    }
    status.put("profiles", profiles);

    return status;
  }

  /**
   * Check if liquidity provider is enabled.
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Check if a symbol has active liquidity.
   */
  public boolean hasLiquidity(String symbol) {
    return activeSymbols.contains(symbol.toUpperCase());
  }
}