package com.yuyay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YuyayApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuyayApplication.class, args);
    }
}
