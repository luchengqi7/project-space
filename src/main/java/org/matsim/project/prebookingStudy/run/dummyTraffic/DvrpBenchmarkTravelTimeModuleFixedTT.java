package org.matsim.project.prebookingStudy.run.dummyTraffic;

import com.google.inject.Key;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpTravelTimeModule;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.util.TravelTime;

public class DvrpBenchmarkTravelTimeModuleFixedTT extends AbstractModule {
    private final double travelTimeOverEstimation;

    public DvrpBenchmarkTravelTimeModuleFixedTT(double travelTimeOverEstimation) {
        this.travelTimeOverEstimation = travelTimeOverEstimation;
    }

    public void install() {
        addTravelTimeBinding(DvrpTravelTimeModule.DVRP_ESTIMATED).toInstance
                (new QSimFreeSpeedTravelTimeFixed(getConfig().qsim().getTimeStepSize(), travelTimeOverEstimation));

        addTravelTimeBinding(TransportMode.car).to(
                Key.get(TravelTime.class, Names.named(DvrpTravelTimeModule.DVRP_ESTIMATED)));
    }
}

