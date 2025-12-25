package com.example.exchange;

import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.io.ByteArrayInputStream;
import java.util.UUID;

public class TestClient implements Application {
    
    private SessionID sessionId;
    
    public static void main(String[] args) throws Exception {
        String config = """
            [default]
            ConnectionType=initiator
            ReconnectInterval=5
            HeartBtInt=30
            StartTime=00:00:00
            EndTime=23:59:59
            FileStorePath=target/data/client
            FileLogPath=target/logs/client
            
            [session]
            BeginString=FIX.4.4
            SenderCompID=CLIENT1
            TargetCompID=EXCHANGE
            SocketConnectHost=localhost
            SocketConnectPort=9876
            DataDictionary=FIX44.xml
            """;
        
        TestClient client = new TestClient();
        SessionSettings settings = new SessionSettings(
            new ByteArrayInputStream(config.getBytes()));
        
        SocketInitiator initiator = new SocketInitiator(
            client,
            new FileStoreFactory(settings),
            settings,
            new ScreenLogFactory(settings),
            new DefaultMessageFactory()
        );
        
        initiator.start();
        
        Thread.sleep(2000); // Wait for connection
        
        // Send test orders
        client.sendOrder("AAPL", Side.BUY, 100, 150.00);
        Thread.sleep(500);
        client.sendOrder("AAPL", Side.SELL, 50, 149.50);
        Thread.sleep(500);
        client.sendOrder("AAPL", Side.BUY, 30, 149.50);
        
        Thread.sleep(5000);
        initiator.stop();
    }
    
    private void sendOrder(String symbol, char side, int qty, double price) {
        try {
            NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(UUID.randomUUID().toString()),
                new Side(side),
                new TransactTime(),
                new OrdType(OrdType.LIMIT)
            );
            
            order.set(new Symbol(symbol));
            order.set(new OrderQty(qty));
            order.set(new Price(price));
            order.set(new TimeInForce(TimeInForce.DAY));
            
            Session.sendToTarget(order, sessionId);
            System.out.println("Sent order: " + side + " " + qty + " " + symbol + " @ " + price);
            
        } catch (SessionNotFound e) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void onCreate(SessionID sessionId) {
        this.sessionId = sessionId;
    }
    
    @Override
    public void onLogon(SessionID sessionId) {
        System.out.println("Logged on: " + sessionId);
    }
    
    @Override
    public void onLogout(SessionID sessionId) {
        System.out.println("Logged out: " + sessionId);
    }
    
    @Override
    public void toAdmin(Message message, SessionID sessionId) {}
    
    @Override
    public void fromAdmin(Message message, SessionID sessionId) {}
    
    @Override
    public void toApp(Message message, SessionID sessionId) {}
    
    @Override
    public void fromApp(Message message, SessionID sessionId) {
        System.out.println("Received: " + message);
    }
}
