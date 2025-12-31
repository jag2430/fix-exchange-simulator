package com.example.exchange.liquidity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that fetches company data from Finnhub and classifies stocks
 * into tiers based on market cap.
 *
 * Tiers:
 *   MEGA_CAP:  > $500B  (AAPL, MSFT, NVDA, etc.)
 *   LARGE_CAP: $50B - $500B (AMD, NFLX, etc.)
 *   MID_CAP:   $10B - $50B (SNAP, ROKU, etc.)
 *   SMALL_CAP: < $10B
 */
@Slf4j
@Service
public class LiquidityProfileService {

  private static final String FINNHUB_URL = "https://finnhub.io/api/v1";

  // Market cap thresholds (in USD)
  private static final long MEGA_CAP_THRESHOLD = 500_000_000_000L;   // $500B
  private static final long LARGE_CAP_THRESHOLD = 50_000_000_000L;   // $50B
  private static final long MID_CAP_THRESHOLD = 10_000_000_000L;     // $10B

  @Value("${liquidity-provider.finnhub.api-key:}")
  private String apiKey;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Map<String, LiquidityProfile> profileCache = new ConcurrentHashMap<>();

  public LiquidityProfileService() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void init() {
    log.info("LiquidityProfileService initialized");
    log.info("  Tier thresholds:");
    log.info("    MEGA_CAP:  > $500B");
    log.info("    LARGE_CAP: $50B - $500B");
    log.info("    MID_CAP:   $10B - $50B");
    log.info("    SMALL_CAP: < $10B");
  }

  /**
   * Get or create a liquidity profile for a symbol.
   * Fetches from Finnhub if not cached.
   */
  public LiquidityProfile getProfile(String symbol) {
    String upperSymbol = symbol.toUpperCase();

    // Check cache first
    LiquidityProfile cached = profileCache.get(upperSymbol);
    if (cached != null) {
      log.debug("Profile cache hit for {}: {} ({})",
          upperSymbol, cached.getTier(), cached.getFormattedMarketCap());
      return cached;
    }

    // Fetch from Finnhub
    LiquidityProfile profile = fetchAndClassify(upperSymbol);
    profileCache.put(upperSymbol, profile);

    log.info("Classified {}: {} ({}) - spread: {} bps, base qty: {}",
        upperSymbol,
        profile.getTier(),
        profile.getFormattedMarketCap(),
        profile.getBaseSpreadBps(),
        profile.getBaseQuantity());

    return profile;
  }

  /**
   * Fetch company profile from Finnhub and classify by market cap.
   */
  private LiquidityProfile fetchAndClassify(String symbol) {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("No Finnhub API key - using UNKNOWN tier for {}", symbol);
      return LiquidityProfile.forTier(symbol, LiquidityProfile.Tier.UNKNOWN, null);
    }

    try {
      String url = String.format("%s/stock/profile2?symbol=%s&token=%s",
          FINNHUB_URL, symbol, apiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(5))
          .build();

      long startTime = System.currentTimeMillis();
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());
      long elapsed = System.currentTimeMillis() - startTime;

      log.debug("Finnhub profile fetch for {} took {}ms", symbol, elapsed);

      if (response.statusCode() == 200) {
        return parseProfileResponse(symbol, response.body());
      } else if (response.statusCode() == 429) {
        log.warn("Finnhub rate limit reached for profile {}", symbol);
      } else {
        log.warn("Finnhub profile API returned {} for {}",
            response.statusCode(), symbol);
      }

    } catch (Exception e) {
      log.error("Failed to fetch profile from Finnhub for {}: {}",
          symbol, e.getMessage());
    }

    // Fallback to unknown tier
    return LiquidityProfile.forTier(symbol, LiquidityProfile.Tier.UNKNOWN, null);
  }

  /**
   * Parse Finnhub profile response and classify the stock.
   *
   * Response format:
   * {
   *   "country": "US",
   *   "currency": "USD",
   *   "exchange": "NASDAQ",
   *   "name": "Apple Inc",
   *   "ticker": "AAPL",
   *   "marketCapitalization": 3417615.25,  // In MILLIONS
   *   ...
   * }
   */
  private LiquidityProfile parseProfileResponse(String symbol, String jsonResponse) {
    try {
      JsonNode root = objectMapper.readTree(jsonResponse);

      // Check if we got an empty response (unknown symbol)
      if (!root.has("marketCapitalization") || root.path("marketCapitalization").isNull()) {
        log.warn("No market cap data for {} - using UNKNOWN tier", symbol);
        return LiquidityProfile.forTier(symbol, LiquidityProfile.Tier.UNKNOWN, null);
      }

      // Finnhub returns market cap in MILLIONS, so multiply by 1M
      double marketCapMillions = root.path("marketCapitalization").asDouble();
      BigDecimal marketCap = BigDecimal.valueOf(marketCapMillions * 1_000_000);

      // Classify based on market cap
      LiquidityProfile.Tier tier = classifyByMarketCap(marketCap.longValue());

      String companyName = root.path("name").asText("Unknown");
      log.debug("Fetched profile for {}: {} - Market Cap: ${}",
          symbol, companyName, String.format("%,.0f", marketCap));

      return LiquidityProfile.forTier(symbol, tier, marketCap);

    } catch (Exception e) {
      log.warn("Failed to parse Finnhub profile for {}: {}", symbol, e.getMessage());
      return LiquidityProfile.forTier(symbol, LiquidityProfile.Tier.UNKNOWN, null);
    }
  }

  /**
   * Classify stock tier based on market cap.
   */
  private LiquidityProfile.Tier classifyByMarketCap(long marketCap) {
    if (marketCap >= MEGA_CAP_THRESHOLD) {
      return LiquidityProfile.Tier.MEGA_CAP;
    } else if (marketCap >= LARGE_CAP_THRESHOLD) {
      return LiquidityProfile.Tier.LARGE_CAP;
    } else if (marketCap >= MID_CAP_THRESHOLD) {
      return LiquidityProfile.Tier.MID_CAP;
    } else {
      return LiquidityProfile.Tier.SMALL_CAP;
    }
  }

  /**
   * Check if a profile is cached.
   */
  public boolean hasProfile(String symbol) {
    return profileCache.containsKey(symbol.toUpperCase());
  }

  /**
   * Get all cached profiles (for monitoring).
   */
  public Map<String, LiquidityProfile> getAllProfiles() {
    return Map.copyOf(profileCache);
  }

  /**
   * Clear the profile cache.
   */
  public void clearCache() {
    profileCache.clear();
    log.info("Profile cache cleared");
  }

  /**
   * Get profile from cache only (no fetch).
   */
  public LiquidityProfile getCachedProfile(String symbol) {
    return profileCache.get(symbol.toUpperCase());
  }
}