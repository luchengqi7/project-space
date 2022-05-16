package org.matsim.project.prebookingStudy.run.dummyTraffic;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpTravelTimeModule;
import org.matsim.contrib.dvrp.util.TimeDiscretizer;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.util.TravelTime;

import java.net.URL;

public class DvrpBenchmarkTravelTimeModuleFixedTT extends AbstractModule {
    @Inject
    private DvrpConfigGroup dvrpCfg;

    private final double travelTimeOverEstimation;

    public DvrpBenchmarkTravelTimeModuleFixedTT(double travelTimeOverEstimation) {
        this.travelTimeOverEstimation = travelTimeOverEstimation;
    }

    public void install() {
        if (dvrpCfg.getInitialTravelTimesFile() != null) {
            addTravelTimeBinding(DvrpTravelTimeModule.DVRP_ESTIMATED).toProvider(() -> {
                URL url = dvrpCfg.getInitialTravelTimesUrl(getConfig().getContext());
                var timeDiscretizer = new TimeDiscretizer(getConfig().travelTimeCalculator());
                var linkTravelTimes = DvrpOfflineTravelTimes.loadLinkTravelTimes(timeDiscretizer, url);
                return DvrpOfflineTravelTimes.asTravelTime(timeDiscretizer, linkTravelTimes);
            }).asEagerSingleton();
        } else {
            addTravelTimeBinding(DvrpTravelTimeModule.DVRP_ESTIMATED).toInstance
                            (new QSimFreeSpeedTravelTimeFixed(getConfig().qsim().getTimeStepSize(), travelTimeOverEstimation));
        }

        // Because TravelTimeCalculatorModule is not installed for benchmarking, we need to add a binding
        // for the car mode
        addTravelTimeBinding(TransportMode.car).to(
                Key.get(TravelTime.class, Names.named(DvrpTravelTimeModule.DVRP_ESTIMATED)));
    }
}

