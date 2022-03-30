package org.matsim.project.prebookingStudy.run.rebalancing;

import org.matsim.contrib.drt.analysis.zonal.DrtZone;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

public class RuralScenarioRebalancingTargetCalculator implements RebalancingTargetCalculator {
    private final ZonalDemandEstimator demandEstimator;
    private final double demandEstimationPeriod;
    private final double lookAheadTime;

    public RuralScenarioRebalancingTargetCalculator(ZonalDemandEstimator demandEstimator, double demandEstimationPeriod, double lookAheadTime) {
        this.demandEstimator = demandEstimator;
        this.demandEstimationPeriod = demandEstimationPeriod;
        this.lookAheadTime = lookAheadTime;
    }

    @Override
    public ToDoubleFunction<DrtZone> calculate(double time, Map<DrtZone, List<DvrpVehicle>> rebalancableVehiclesPerZone) {
        return transform(time);
    }

    /**
     * Custom transform function (simple example)
     */
    private ToDoubleFunction<DrtZone> transform(double time) {
        ToDoubleFunction<DrtZone> originalFunction = demandEstimator.getExpectedDemand(time, demandEstimationPeriod);
        ToDoubleFunction<DrtZone> originalFunctionWithLookAhead = demandEstimator.getExpectedDemand(time + lookAheadTime, demandEstimationPeriod);

        return drtZone -> {
            double originalValue = Math.max(originalFunction.applyAsDouble(drtZone), originalFunctionWithLookAhead.applyAsDouble(drtZone));
            if (originalValue > 0) {
                return 1;
            }
            return 0;
        };
    }
}
