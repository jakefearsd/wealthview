package com.wealthview.projection;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrialPassRunnerTest {

    @Test
    void run_tentPlan_givesEveryTrialTheSchedulesTarget() {
        int years = 5;
        int trials = 3;
        var plan = new TrialTentPlan(5.0, 0.0, new double[] {1, 0, 0, 0}, new double[4],
                List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH), 0, 0, 0, null);
        var paths = new TentPaths(new double[trials][(years + 2) * 4], null, null);
        double[][] zeros = new double[trials][years];
        var factory = TrialConfigFactory.builder(new PoolSimSetup(10_000_000, 0, 0, "taxable_first", true))
                .returns(zeros, zeros, zeros)
                .ages(65, 73, 60)
                .taxableBasis(10_000_000)
                .tent(plan, paths)
                .build();
        double[] floors = {40_000, 40_000, 40_000, 40_000, 40_000};
        double[] zero = new double[years];
        double expected = factory.tentTargetFor(floors, zero, zero, zero);
        var simulator = mock(TrialSimulator.class);
        when(simulator.simulateTrial(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new TrialSimulator.TrialResult(1, 1, null, false, true));

        new TrialPassRunner(simulator).run(trials, years, factory, zero, zero, floors, zero, null, false, null);

        var captor = ArgumentCaptor.forClass(TrialSimulator.SimulationConfig.class);
        verify(simulator, times(trials)).simulateTrial(any(), any(), any(), any(), anyInt(), captor.capture());
        List<Double> totals = new ArrayList<>();
        captor.getAllValues().forEach(config -> totals.add(config.tent().total()));
        assertThat(expected).isCloseTo(200_000, within(1e-6));
        assertThat(totals).allSatisfy(total -> assertThat(total).isCloseTo(expected, within(1e-6)));
    }
}
