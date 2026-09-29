package com.zc.smartrefundagentgraphdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class SmartRefundAgentGraphDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartRefundAgentGraphDemoApplication.class, args);
    }

}
