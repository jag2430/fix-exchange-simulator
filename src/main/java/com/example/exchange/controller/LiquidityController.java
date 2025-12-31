package com.example.exchange.controller;

import com.example.exchange.liquidity.FinnhubPriceService;
import com.example.exchange.liquidity.LiquidityProfile;
import com.example.exchange.liquidity.LiquidityProfileService;
import com.example.exchange.liquidity.LiquidityProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for liquidity provider monitoring and control.
 */
@RestController
@RequestMapping("/api/liquidity")
@RequiredArgsConstructor
public class LiquidityController {

  private final LiquidityProvider liquidityProvider;
  private final FinnhubPriceService priceService;
  private final LiquidityProfileService profileService;

  /**
   * Get liquidity provider status including all active profiles.
   */
  @GetMapping("/status")
  public Map<String, Object> getStatus() {
    return liquidityProvider.getStatus();
  }

  /**
   * Get profile for a specific symbol (will fetch from Finnhub if not cached).
   */
  @GetMapping("/profile/{symbol}")
  public Map<String, Object> getProfile(@PathVariable String symbol) {
    String upperSymbol = symbol.toUpperCase();

    long startTime = System.currentTimeMillis();
    LiquidityProfile profile = profileService.getProfile(upperSymbol);
    long elapsed = System.currentTimeMillis() - startTime;

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", upperSymbol);
    response.put("tier", profile.getTier().name());
    response.put("marketCap", profile.getFormattedMarketCap());
    response.put("marketCapRaw", profile.getMarketCap());
    response.put("baseSpreadBps", profile.getBaseSpreadBps());
    response.put("levelIncrementBps", profile.getLevelIncrementBps());
    response.put("baseQuantity", profile.getBaseQuantity());
    response.put("quantityMultiplier", profile.getQuantityMultiplier());
    response.put("levels", profile.getLevels());
    response.put("fetchTimeMs", elapsed);

    return response;
  }

  /**
   * Get all cached profiles.
   */
  @GetMapping("/profiles")
  public Map<String, Object> getAllProfiles() {
    Map<String, LiquidityProfile> profiles = profileService.getAllProfiles();

    Map<String, Object> response = new HashMap<>();
    profiles.forEach((symbol, profile) -> {
      response.put(symbol, Map.of(
          "tier", profile.getTier().name(),
          "marketCap", profile.getFormattedMarketCap(),
          "spreadBps", profile.getBaseSpreadBps(),
          "baseQuantity", profile.getBaseQuantity()
      ));
    });

    return response;
  }

  /**
   * Manually setup liquidity for a symbol.
   */
  @PostMapping("/setup/{symbol}")
  public ResponseEntity<Map<String, Object>> setupLiquidity(@PathVariable String symbol) {
    if (!liquidityProvider.isEnabled()) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "Liquidity provider is disabled",
          "symbol", symbol
      ));
    }

    long startTime = System.currentTimeMillis();
    liquidityProvider.setupLiquidity(symbol);
    long elapsed = System.currentTimeMillis() - startTime;

    LiquidityProfile profile = profileService.getCachedProfile(symbol.toUpperCase());

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", symbol.toUpperCase());
    response.put("status", "Liquidity setup complete");
    response.put("elapsedMs", elapsed);
    response.put("hasLiquidity", liquidityProvider.hasLiquidity(symbol));

    if (profile != null) {
      response.put("tier", profile.getTier().name());
      response.put("marketCap", profile.getFormattedMarketCap());
      response.put("spreadBps", profile.getBaseSpreadBps());
      response.put("baseQuantity", profile.getBaseQuantity());
    }

    return ResponseEntity.ok(response);
  }

  /**
   * Check if a symbol has liquidity.
   */
  @GetMapping("/check/{symbol}")
  public Map<String, Object> checkLiquidity(@PathVariable String symbol) {
    String upperSymbol = symbol.toUpperCase();
    LiquidityProfile profile = profileService.getCachedProfile(upperSymbol);

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", upperSymbol);
    response.put("hasLiquidity", liquidityProvider.hasLiquidity(symbol));
    response.put("enabled", liquidityProvider.isEnabled());

    if (profile != null) {
      response.put("tier", profile.getTier().name());
      response.put("marketCap", profile.getFormattedMarketCap());
    }

    return response;
  }

  /**
   * Get current price for a symbol from Finnhub.
   */
  @GetMapping("/price/{symbol}")
  public Map<String, Object> getPrice(@PathVariable String symbol) {
    String upperSymbol = symbol.toUpperCase();

    boolean wasCached = priceService.hasCachedPrice(upperSymbol);

    long startTime = System.currentTimeMillis();
    BigDecimal price = priceService.getPrice(upperSymbol);
    long elapsed = System.currentTimeMillis() - startTime;

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", upperSymbol);
    response.put("cached", wasCached);
    response.put("fetchTimeMs", elapsed);

    if (price != null) {
      response.put("price", price);
    } else {
      response.put("error", "Unable to fetch price");
    }

    return response;
  }

  /**
   * Get all cached prices.
   */
  @GetMapping("/prices")
  public Map<String, BigDecimal> getAllPrices() {
    return priceService.getAllCachedPrices();
  }

  /**
   * Clear all caches (prices and profiles).
   */
  @PostMapping("/cache/clear")
  public Map<String, String> clearCache() {
    priceService.clearCache();
    profileService.clearCache();
    return Map.of("status", "Price and profile caches cleared");
  }

  /**
   * Force refresh price for a symbol.
   */
  @PostMapping("/price/refresh/{symbol}")
  public Map<String, Object> refreshPrice(@PathVariable String symbol) {
    String upperSymbol = symbol.toUpperCase();

    long startTime = System.currentTimeMillis();
    BigDecimal price = priceService.refreshPrice(upperSymbol);
    long elapsed = System.currentTimeMillis() - startTime;

    Map<String, Object> response = new HashMap<>();
    response.put("symbol", upperSymbol);
    response.put("refreshed", true);
    response.put("fetchTimeMs", elapsed);

    if (price != null) {
      response.put("price", price);
    } else {
      response.put("error", "Unable to refresh price");
    }

    return response;
  }
}