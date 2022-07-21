package org.matsim.project.congestionAwareDrt;

import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

public class CongestionAwareDrtOptimizer implements DrtOptimizer {
    @Override
    public void requestSubmitted(Request request) {

    }

    @Override
    public void nextTask(DvrpVehicle dvrpVehicle) {

    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent mobsimBeforeSimStepEvent) {
        double now = mobsimBeforeSimStepEvent.getSimulationTime();


    }
}
