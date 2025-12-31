package com.example.exchange.liquidity;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Holds liquidity parameters for a specific stock or tier.
 */
@Data
@Builder
public class LiquidityProfile {

  public enum Tier {
    MEGA_CAP,    // > $500B market cap
    LARGE_CAP,   // $50B - $500B
    MID_CAP,     // $10B - $50B
    SMALL_CAP,   // < $10B
    UNKNOWN      // Fallback when we can't classify
  }

  private String symbol;
  private Tier tier;
  private BigDecimal marketCap;

  private int baseSpreadBps;
  private int levelIncrementBps;
  private int baseQuantity;
  private int quantityMultiplier;
  private int levels;

  /**
   * Create a profile for a given tier with default settings.
   */
  public static LiquidityProfile forTier(String symbol, Tier tier, BigDecimal marketCap) {
    return switch (tier) {
      case MEGA_CAP -> LiquidityProfile.builder()
          .symbol(symbol)
          .tier(tier)
          .marketCap(marketCap)
          .baseSpreadBps(1)
          .levelIncrementBps(1)
          .baseQuantity(1000)
          .quantityMultiplier(2)
          .levels(5)
          .build();

      case LARGE_CAP -> LiquidityProfile.builder()
          .symbol(symbol)
          .tier(tier)
          .marketCap(marketCap)
          .baseSpreadBps(2)
          .levelIncrementBps(2)
          .baseQuantity(500)
          .quantityMultiplier(2)
          .levels(5)
          .build();

      case MID_CAP -> LiquidityProfile.builder()
          .symbol(symbol)
          .tier(tier)
          .marketCap(marketCap)
          .baseSpreadBps(5)
          .levelIncrementBps(3)
          .baseQuantity(200)
          .quantityMultiplier(2)
          .levels(5)
          .build();

      case SMALL_CAP -> LiquidityProfile.builder()
          .symbol(symbol)
          .tier(tier)
          .marketCap(marketCap)
          .baseSpreadBps(10)
          .levelIncrementBps(5)
          .baseQuantity(100)
          .quantityMultiplier(2)
          .levels(5)
          .build();

      case UNKNOWN -> LiquidityProfile.builder()
          .symbol(symbol)
          .tier(tier)
          .marketCap(marketCap)
          .baseSpreadBps(10)
          .levelIncrementBps(5)
          .baseQuantity(100)
          .quantityMultiplier(2)
          .levels(5)
          .build();
    };
  }

  /**
   * Format market cap for display (e.g., "$3.5T", "$150B", "$5.2B")
   */
  public String getFormattedMarketCap() {
    if (marketCap == null) {
      return "Unknown";
    }

    double cap = marketCap.doubleValue();

    if (cap >= 1_000_000_000_000L) {
      return String.format("$%.1fT", cap / 1_000_000_000_000L);
    } else if (cap >= 1_000_000_000L) {
      return String.format("$%.1fB", cap / 1_000_000_000L);
    } else if (cap >= 1_000_000L) {
      return String.format("$%.1fM", cap / 1_000_000L);
    } else {
      return String.format("$%.0f", cap);
    }
  }
}