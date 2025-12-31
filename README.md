# FIX Exchange Simulator

A Spring Boot application that simulates a financial exchange using QuickFIX/J for FIX protocol communication, **with built-in liquidity provider** for realistic order execution.

## Features

- FIX 4.4 protocol support
- Order matching engine with price-time priority
- **Liquidity Provider** - Simulated market maker that posts bid/ask quotes
- **Finnhub Integration** - Real-time reference prices from Finnhub API
- Limit and market order support
- Order cancellation and amendment
- REST API for monitoring order books and liquidity status
- Multiple client session support

## How It Works

When you send an order:

1. **First order for a symbol** (~100-200ms): Fetches real price from Finnhub, posts market maker quotes
2. **Subsequent orders** (instant): Quotes already exist, matches immediately
3. **Your order matches** against MM quotes and fills

```
Without Liquidity Provider:          With Liquidity Provider:
┌────────────────────────┐           ┌────────────────────────┐
│ Your BUY 100 AAPL      │           │ Your BUY 100 AAPL      │
│         ↓              │           │         ↓              │
│ Order rests in book    │           │ Matches MM ask quote   │
│ (no counter-order)     │           │         ↓              │
│         ↓              │           │ FILLED @ $150.15       │
│ Nothing happens        │           └────────────────────────┘
└────────────────────────┘
```

## Prerequisites

- Java 17+
- Maven 3.6+
- Finnhub API key (free at https://finnhub.io/register)

## Quick Start

### 1. Get Finnhub API Key

Sign up for free at https://finnhub.io/register

### 2. Set Environment Variable

```bash
export FINNHUB_API_KEY=your_api_key_here
```

Or add to `application.yml`:
```yaml
liquidity-provider:
  finnhub:
    api-key: your_api_key_here
```

### 3. Build and Run

```bash
cd fix-exchange-simulator
mvn clean package
mvn spring-boot:run
```

The exchange will:
- Start a FIX acceptor on port **9876**
- Start a REST API on port **8080**
- Initialize liquidity provider (quotes posted on first order per symbol)

### 4. Test with the Test Client

In a separate terminal:

```bash
mvn exec:java -Dexec.mainClass="com.example.exchange.TestClient" -Dexec.classpathScope=test
```

You should see orders **filling immediately** against MM liquidity!

## REST API Endpoints

### Order Book

```bash
# Get order book for a symbol
curl http://localhost:8080/api/exchange/orderbook/AAPL

# List all active symbols
curl http://localhost:8080/api/exchange/symbols
```

### Liquidity Provider

```bash
# Get liquidity provider status
curl http://localhost:8080/api/liquidity/status

# Pre-warm liquidity for a symbol (before trading)
curl -X POST http://localhost:8080/api/liquidity/setup/AAPL

# Check if symbol has liquidity
curl http://localhost:8080/api/liquidity/check/AAPL

# Get current price from Finnhub
curl http://localhost:8080/api/liquidity/price/AAPL

# Get all cached prices
curl http://localhost:8080/api/liquidity/prices

# Force refresh price
curl -X POST http://localhost:8080/api/liquidity/price/refresh/AAPL

# Clear price cache
curl -X POST http://localhost:8080/api/liquidity/cache/clear
```

## Configuration

All settings in `application.yml`:

```yaml
liquidity-provider:
  enabled: true                    # Enable/disable MM
  
  finnhub:
    api-key: ${FINNHUB_API_KEY:}   # Your API key
    cache-ttl-seconds: 30          # Price cache duration
  
  levels: 5                        # Price levels per side
  base-spread-bps: 10              # Spread at best price (0.10%)
  level-increment-bps: 5           # Additional spread per level
  base-quantity: 100               # Size at best bid/ask
  quantity-multiplier: 2           # Size multiplier per level
  refresh-interval-ms: 5000        # Quote refresh interval
  fallback-price: 100.00           # Price when Finnhub unavailable
```

### Resulting Quote Structure

With default settings and $150.00 reference price:

| Level | Offset | Bid Price | Ask Price | Quantity |
|-------|--------|-----------|-----------|----------|
| 0     | 10 bps | $149.85   | $150.15   | 100      |
| 1     | 15 bps | $149.78   | $150.23   | 200      |
| 2     | 20 bps | $149.70   | $150.30   | 400      |
| 3     | 25 bps | $149.63   | $150.38   | 800      |
| 4     | 30 bps | $149.55   | $150.45   | 1,600    |

**Total liquidity per side: 3,100 shares**

## FIX Session Configuration

Clients should connect with:
- Host: localhost
- Port: 9876
- BeginString: FIX.4.4
- SenderCompID: BANZAI
- TargetCompID: EXEC

## Supported FIX Messages

### Incoming
- NewOrderSingle (D)
- OrderCancelRequest (F)
- OrderCancelReplaceRequest (G)

### Outgoing
- ExecutionReport (8)

## Project Structure

```
fix-exchange-simulator/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/exchange/
│   │   │   ├── ExchangeSimulatorApplication.java
│   │   │   ├── config/
│   │   │   │   └── FixConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── ExchangeController.java
│   │   │   │   └── LiquidityController.java      ← NEW
│   │   │   ├── engine/
│   │   │   │   ├── MatchingEngine.java           ← MODIFIED
│   │   │   │   └── OrderBook.java
│   │   │   ├── fix/
│   │   │   │   └── ExchangeFixApplication.java
│   │   │   ├── liquidity/                        ← NEW
│   │   │   │   ├── LiquidityProvider.java
│   │   │   │   └── FinnhubPriceService.java
│   │   │   └── model/
│   │   │       ├── Execution.java
│   │   │       └── Order.java
│   │   └── resources/
│   │       ├── application.yml                   ← MODIFIED
│   │       └── quickfix-server.cfg
│   └── test/java/com/example/exchange/
│       └── TestClient.java
```

## Integration with fix-client

This exchange works directly with your `fix-client` application. The FIX session config is compatible:

```
fix-client (BANZAI) ←→ FIX 4.4 ←→ fix-exchange-simulator (EXEC)
                                          │
                                          ├── Matching Engine
                                          └── Liquidity Provider
                                                    │
                                                    └── Finnhub API
```

Orders from your Trading UI will:
1. Flow through fix-client
2. Arrive at the exchange via FIX
3. Match against MM liquidity
4. Return fill execution reports
5. Appear in your Portfolio Blotter!

## Disabling Liquidity Provider

If you want the "pure" exchange without automatic liquidity:

```yaml
liquidity-provider:
  enabled: false
```

Orders will then rest in the book until matching counter-orders arrive.

## License

MIT
