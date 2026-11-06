package io.aria.conductor.dashboard.report;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmendReportRequest {

    @NotBlank(message = "Instruction is required")
    private String instruction;
}
