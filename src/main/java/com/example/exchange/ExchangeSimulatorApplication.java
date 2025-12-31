package com.example.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExchangeSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExchangeSimulatorApplication.class, args);
  }
}