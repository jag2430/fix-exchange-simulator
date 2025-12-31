package com.example.exchange.liquidity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for fetching real-time prices from Finnhub.
 * Caches prices to avoid excessive API calls.
 */
@Slf4j
@Service
public class FinnhubPriceService {

  private static final String FINNHUB_REST_URL = "https://finnhub.io/api/v1";

  @Value("${liquidity-provider.finnhub.api-key:}")
  private String apiKey;

  @Value("${liquidity-provider.finnhub.cache-ttl-seconds:30}")
  private long cacheTtlSeconds;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

  public FinnhubPriceService() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void init() {
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("=================================================");
      log.warn("FINNHUB API KEY NOT CONFIGURED!");
      log.warn("Liquidity provider will use fallback prices.");
      log.warn("Set liquidity-provider.finnhub.api-key in application.yml");
      log.warn("Get your FREE API key at: https://finnhub.io/register");
      log.warn("=================================================");
    } else {
      log.info("Finnhub price service initialized with API key");
    }
  }

  /**
   * Get price for a symbol. Uses cache if available and not expired.
   * This is a BLOCKING call - will wait for Finnhub response if not cached.
   */
  public BigDecimal getPrice(String symbol) {
    String upperSymbol = symbol.toUpperCase();

    // Check cache first
    CachedPrice cached = priceCache.get(upperSymbol);
    if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
      log.debug("Cache hit for {}: ${}", upperSymbol, cached.price);
      return cached.price;
    }

    // Fetch from Finnhub (blocking)
    BigDecimal price = fetchFromFinnhub(upperSymbol);

    if (price != null) {
      priceCache.put(upperSymbol, new CachedPrice(price));
      log.info("Fetched {} price from Finnhub: ${}", upperSymbol, price);
    }

    return price;
  }

  /**
   * Check if a price is cached (without fetching).
   */
  public boolean hasCachedPrice(String symbol) {
    CachedPrice cached = priceCache.get(symbol.toUpperCase());
    return cached != null && !cached.isExpired(cacheTtlSeconds);
  }

  /**
   * Get cached price without fetching. Returns null if not cached or expired.
   */
  public BigDecimal getCachedPrice(String symbol) {
    CachedPrice cached = priceCache.get(symbol.toUpperCase());
    if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
      return cached.price;
    }
    return null;
  }

  /**
   * Force refresh price for a symbol.
   */
  public BigDecimal refreshPrice(String symbol) {
    String upperSymbol = symbol.toUpperCase();
    priceCache.remove(upperSymbol);
    return getPrice(upperSymbol);
  }

  /**
   * Fetch price from Finnhub API.
   */
  private BigDecimal fetchFromFinnhub(String symbol) {
    if (apiKey == null || apiKey.isEmpty()) {
      log.debug("No API key configured, cannot fetch price for {}", symbol);
      return null;
    }

    try {
      String url = String.format("%s/quote?symbol=%s&token=%s",
          FINNHUB_REST_URL, symbol, apiKey);

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

      log.debug("Finnhub API call for {} took {}ms", symbol, elapsed);

      if (response.statusCode() == 200) {
        return parseQuoteResponse(symbol, response.body());
      } else if (response.statusCode() == 429) {
        log.warn("Finnhub rate limit reached for {}", symbol);
      } else {
        log.warn("Finnhub API returned {} for {}: {}",
            response.statusCode(), symbol, response.body());
      }

    } catch (Exception e) {
      log.error("Failed to fetch price from Finnhub for {}: {}", symbol, e.getMessage());
    }

    return null;
  }

  /**
   * Parse Finnhub quote response.
   * Response format: {"c":150.0,"d":1.5,"dp":1.0,"h":151.0,"l":149.0,"o":149.5,"pc":148.5}
   * c = current price, d = change, dp = percent change, h = high, l = low, o = open, pc = previous close
   */
  private BigDecimal parseQuoteResponse(String symbol, String jsonResponse) {
    try {
      JsonNode root = objectMapper.readTree(jsonResponse);

      if (root.has("error")) {
        log.warn("Finnhub error for {}: {}", symbol, root.path("error").asText());
        return null;
      }

      double currentPrice = root.path("c").asDouble();
      if (currentPrice <= 0) {
        log.warn("No valid price for {} from Finnhub (c={})", symbol, currentPrice);
        return null;
      }

      return BigDecimal.valueOf(currentPrice).setScale(2, RoundingMode.HALF_UP);

    } catch (Exception e) {
      log.warn("Failed to parse Finnhub response for {}: {}", symbol, e.getMessage());
      return null;
    }
  }

  /**
   * Clear the entire price cache.
   */
  public void clearCache() {
    priceCache.clear();
    log.info("Price cache cleared");
  }

  /**
   * Get all currently cached prices (for monitoring/debugging).
   */
  public Map<String, BigDecimal> getAllCachedPrices() {
    Map<String, BigDecimal> result = new ConcurrentHashMap<>();
    priceCache.forEach((symbol, cached) -> {
      if (!cached.isExpired(cacheTtlSeconds)) {
        result.put(symbol, cached.price);
      }
    });
    return result;
  }

  /**
   * Internal class for cached price with timestamp.
   */
  private static class CachedPrice {
    final BigDecimal price;
    final long timestamp;

    CachedPrice(BigDecimal price) {
      this.price = price;
      this.timestamp = System.currentTimeMillis();
    }

    boolean isExpired(long ttlSeconds) {
      return System.currentTimeMillis() - timestamp > ttlSeconds * 1000;
    }
  }
}