package org.matsim.project.drt_zone_generation.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.drt.analysis.zonal.DrtZonalSystem;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.*;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.project.drt_zone_generation.HeuristicRebalancingZoneGenerator;
import picocli.CommandLine;

import javax.annotation.Nullable;

public class RunDrtSimulations extends MATSimApplication {

    @CommandLine.Option(names = "--new-zone", defaultValue = "false", description = "enable new zonal system")
    private boolean improvedZones;

    public static void main(String[] args) {
        MATSimApplication.run(RunDrtSimulations.class, args);
    }

    @Nullable
    @Override
    protected Config prepareConfig(Config config) {
        config.addModule(new MultiModeDrtConfigGroup());
        config.addModule(new DvrpConfigGroup());
        MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.planCalcScore(), config.plansCalcRoute());
        return config;
    }

    @Override
    protected void prepareScenario(Scenario scenario) {
        scenario.getPopulation()
                .getFactory()
                .getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());
    }

    @Override
    protected void prepareControler(Controler controler) {
        Config config = controler.getConfig();
        MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        controler.addOverridingModule(new DvrpModule());
        controler.addOverridingModule(new MultiModeDrtModule());
        controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeDrtConfig));

        if (improvedZones) {
            DrtConfigGroup drtConfigGroup = DrtConfigGroup.getSingleModeDrtConfig(config);
            String outputPathForNetworkWithZones = config.controler().getOutputDirectory() + "/output-network-with-zones.xml.gz";
            controler.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.mode) {
                @Override
                public void install() {
                    bindModal(DrtZonalSystem.class).toProvider(modalProvider(getter ->
                            new HeuristicRebalancingZoneGenerator.ZoneGeneratorBuilder(getter.getModal(Network.class), outputPathForNetworkWithZones)
                                    .setInputPlans(getter.get(Population.class)).setZoneIterations(10)
                                    .build().compute())).asEagerSingleton();
                }
            });
        }

    }
}
