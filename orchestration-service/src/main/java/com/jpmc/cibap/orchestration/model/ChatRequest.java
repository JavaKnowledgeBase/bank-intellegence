// ChatRequest.java
package com.jpmc.cibap.orchestration.model;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank
    @Size(max = 2000)
    private String message;
}
