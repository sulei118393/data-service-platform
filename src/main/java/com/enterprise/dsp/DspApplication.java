package com.enterprise.dsp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Configuration
public class DspApplication {
    public static void main(String[] args) {
        SpringApplication.run(DspApplication.class, args);
        System.out.println("\n==== 数据服务平台启动成功，管理端: http://localhost:8086/ ====\n");
    }
}
