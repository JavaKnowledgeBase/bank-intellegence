package com.jpmc.cibap.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;

@Configuration
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    @Bean
    public SnsClient snsClient() {
        return SnsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public SesV2Client sesV2Client() {
        return SesV2Client.builder().region(Region.of(region)).build();
    }
}
