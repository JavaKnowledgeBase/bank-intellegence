package com.jpmc.cibap.orchestration.config;

import dev.langchain4j.model.bedrock.BedrockAnthropicStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

@Configuration
public class BedrockModelConfig {

    @Value("${aws.bedrock.model-id:anthropic.claude-3-5-sonnet-20241022-v2:0}")
    private String modelId;
//    @Value("${aws.bedrock.streaming-model-id:anthropic.claude-3-sonnet-20241022-v1:0}")
//    private String streamingModelId;
    @Value("${aws.region:us-east-1}")
    private String region;

    @Bean
    public BedrockAnthropicStreamingChatModel streamingChatModel() {
        return BedrockAnthropicStreamingChatModel.builder()
                .region(Region.of(region))
                .model(modelId)
                .maxTokens(4096)
                .temperature(0.0) // deterministic for banking
                .build();
    }
}
