package org.matsim.project.prebookingStudy.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.analysis.DrtServiceQualityAnalysis;
import org.matsim.project.prebookingStudy.run.rebalancing.RuralScenarioRebalancingTCModule;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "run",
        description = "run drt pre-booking study"
)
public class RunMATSimBenchmark implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--alpha", description = "travel time alpha", defaultValue = "2.0")
    private double alpha;

    @CommandLine.Option(names = "--beta", description = "travel time beta", defaultValue = "1200.0")
    private double beta;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--output", description = "fleet size", defaultValue = "250")
    private String output;

    public static void main(String[] args) {
        new RunMATSimBenchmark().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath.toString(), new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.planCalcScore(), config.plansCalcRoute());

        DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next(); // By default, the first drt config group is the one we are using
        drtConfigGroup.setMaxTravelTimeAlpha(alpha);
        drtConfigGroup.setMaxTravelTimeBeta(beta);
        drtConfigGroup.setMaxWaitTime(7200); //This constraint is no longer important in the school traffic case

        drtConfigGroup.setVehiclesFile("drt-vehicles-with-depot/" + fleetSize + "-8_seater-drt-vehicles.xml");

        config.controler().setOutputDirectory(output);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories().setRouteFactory(DrtRoute.class, new DrtRouteFactory());

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new DvrpModule());
        controler.addOverridingModule(new MultiModeDrtModule());
        controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeDrtConfig));

        // Adding the custom rebalancing target calculator
        for (DrtConfigGroup drtCfg : multiModeDrtConfig.getModalElements()) {
            controler.addOverridingQSimModule(new RuralScenarioRebalancingTCModule(drtCfg, 300));
        }

        controler.run();

        String outputDirectory = controler.getConfig().controler().getOutputDirectory();

        String[] args2 = new String[]{"--directory=" + outputDirectory};
        DrtServiceQualityAnalysis.main(args2);

        String[] input = new String[]{outputDirectory};
        DrtVehicleStoppingTaskWriter.main(input);

        return 0;
    }
}
