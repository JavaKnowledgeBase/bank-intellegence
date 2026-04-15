package com.jpmc.cibap.orchestration.config;

import com.jpmc.cibap.orchestration.agent.BankingPlannerAgent;
import com.jpmc.cibap.orchestration.memory.ConversationMemoryStore;
import com.jpmc.cibap.orchestration.tools.AccountLookupTool;
import com.jpmc.cibap.orchestration.tools.FraudCheckTool;
import com.jpmc.cibap.orchestration.tools.LoanEligibilityTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
public class AgentConfig {

    private final BedrockModelConfig bedrockModelConfig;
    private final ConversationMemoryStore memoryStore;
    private final AccountLookupTool accountLookupTool;
    private final FraudCheckTool fraudCheckTool;
    private final LoanEligibilityTool loanEligibilityTool;

    @Bean
    public BankingPlannerAgent bankingPlannerAgent() {
        return AiServices.builder(BankingPlannerAgent.class)
                .streamingChatLanguageModel(bedrockModelConfig.streamingChatModel())
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(accountLookupTool, fraudCheckTool, loanEligibilityTool)
                .build();
    }
}
