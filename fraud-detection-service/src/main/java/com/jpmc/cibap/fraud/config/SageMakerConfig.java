package com.jpmc.cibap.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;

@Configuration
public class SageMakerConfig {

    @Bean
    public SageMakerRuntimeClient sageMakerRuntimeClient(@Value("${aws.region:us-east-1}") String awsRegion) {
        return SageMakerRuntimeClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
