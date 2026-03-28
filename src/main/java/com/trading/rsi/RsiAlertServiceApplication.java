package com.trading.rsi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RsiAlertServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RsiAlertServiceApplication.class, args);
    }
}
