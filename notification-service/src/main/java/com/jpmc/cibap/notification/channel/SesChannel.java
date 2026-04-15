package com.jpmc.cibap.notification.channel;

import com.jpmc.cibap.notification.model.NotificationRequest;
import com.jpmc.cibap.notification.template.NotificationTemplateService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class SesChannel {

    private final SesV2Client sesClient;
    private final NotificationTemplateService templateService;

    @Value("${aws.ses.from-address:alerts@chase.com}")
    private String fromAddress;

    @Value("${aws.notifications.mock:true}")
    private boolean mockNotifications;

    @CircuitBreaker(name = "ses", fallbackMethod = "fallback")
    public Mono<String> sendEmail(NotificationRequest request) {
        if (mockNotifications) {
            log.info("Mock email notification eventType={} recipient={}", request.getEventType(), request.getRecipient());
            return Mono.just("SES_MOCK_" + request.getMessageUuid());
        }
        return Mono.fromCallable(() -> {
            String htmlBody = templateService.render(request.getTemplateName(), request.getTemplateVars());
            return sesClient.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder().toAddresses(request.getRecipient()).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject(request.getEventType())).charset("UTF-8").build())
                                    .body(Body.builder().html(Content.builder().data(htmlBody).charset("UTF-8").build()).build())
                                    .build())
                            .build())
                    .build()).messageId();
        });
    }

    private String subject(String eventType) {
        return switch (eventType) {
            case "FRAUD_BLOCKED" -> "Action Required: Suspicious Activity on Your Account";
            case "LOAN_APPROVED" -> "Your Loan Pre-Screening is Approved";
            case "LOAN_DECLINED" -> "Update on Your Loan Application";
            default -> "Account Notification";
        };
    }

    public Mono<String> fallback(NotificationRequest request, Exception ex) {
        log.error("SES circuit open - email not sent for customerId={}", request.getCustomerId(), ex);
        return Mono.just("SES_FALLBACK_" + request.getMessageUuid());
    }
}
