package io.github.cuukenn.fincalc.server.web;

import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import io.github.cuukenn.fincalc.server.dto.CalculationRequestAssembler;
import io.github.cuukenn.fincalc.server.dto.CalculationRequestDto;
import io.github.cuukenn.fincalc.server.dto.CalculationResponseAssembler;
import io.github.cuukenn.fincalc.server.dto.CalculationResponseDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calculation endpoint of Plan 2 Task 2: {@code POST /api/v1/calculations} runs one engine
 * calculation and answers with the hand-mapped response DTO (R76). Business failures surface
 * through {@link ApiExceptionHandler} under R77.
 */
@RestController
public class CalculationController {

    private final CalculationFacade facade;
    private final CalculationRequestAssembler requestAssembler = new CalculationRequestAssembler();
    private final CalculationResponseAssembler responseAssembler = new CalculationResponseAssembler();

    public CalculationController(CalculationFacade facade) {
        this.facade = facade;
    }

    /**
     * Runs one calculation.
     *
     * @param request the flat lease/rule request body; never {@code null}
     * @return the schedule, metrics, cash flows, issues and optional §9.7 table
     */
    @PostMapping("/api/v1/calculations")
    public CalculationResponseDto calculate(@RequestBody CalculationRequestDto request) {
        CalculationRequest engineRequest = requestAssembler.assemble(request);
        CalculationResult result = facade.calculate(engineRequest);
        return responseAssembler.assemble(result);
    }
}
