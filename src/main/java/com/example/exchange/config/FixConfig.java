package com.example.exchange.config;

import com.example.exchange.fix.ExchangeFixApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.*;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FixConfig {
    
    private final ExchangeFixApplication exchangeFixApplication;
    
    @Value("${fix.config-file}")
    private String configFile;
    
    private SocketAcceptor acceptor;
    
    @Bean
    public SessionSettings sessionSettings() throws ConfigError {
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream(configFile)) {
            return new SessionSettings(inputStream);
        } catch (Exception e) {
            throw new ConfigError("Failed to load FIX configuration", e);
        }
    }
    
    @Bean
    public MessageStoreFactory messageStoreFactory(SessionSettings settings) {
        return new FileStoreFactory(settings);
    }
    
    @Bean
    public LogFactory logFactory(SessionSettings settings) {
        return new FileLogFactory(settings);
    }
    
    @Bean
    public MessageFactory messageFactory() {
        return new DefaultMessageFactory();
    }
    
    @Bean
    public SocketAcceptor socketAcceptor(
            SessionSettings settings,
            MessageStoreFactory storeFactory,
            LogFactory logFactory,
            MessageFactory messageFactory) throws ConfigError {
        
        acceptor = new SocketAcceptor(
            exchangeFixApplication,
            storeFactory,
            settings,
            logFactory,
            messageFactory
        );
        
        return acceptor;
    }
    
    @Bean
    public AcceptorStarter acceptorStarter(SocketAcceptor acceptor) {
        return new AcceptorStarter(acceptor);
    }
    
    @PreDestroy
    public void stopAcceptor() {
        if (acceptor != null && acceptor.isLoggedOn()) {
            log.info("Stopping FIX acceptor...");
            acceptor.stop();
        }
    }
    
    @RequiredArgsConstructor
    public static class AcceptorStarter {
        private final SocketAcceptor acceptor;
        
        @jakarta.annotation.PostConstruct
        public void start() throws ConfigError {
            log.info("Starting FIX acceptor...");
            acceptor.start();
            log.info("FIX acceptor started on configured ports");
        }
    }
}
