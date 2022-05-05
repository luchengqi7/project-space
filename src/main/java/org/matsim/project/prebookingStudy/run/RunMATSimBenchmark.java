package org.matsim.project.prebookingStudy.run;

import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.routing.DefaultDrtRouteUpdater;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.routing.DrtRouteUpdater;
import org.matsim.contrib.drt.run.*;
import org.matsim.contrib.dvrp.benchmark.DvrpBenchmarkTravelTimeModule;
import org.matsim.contrib.dvrp.router.DefaultMainLegRouter;
import org.matsim.contrib.dvrp.run.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.modal.ModalProviders;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.analysis.SchoolTripsAnalysis;
import org.matsim.project.prebookingStudy.run.rebalancing.RuralScenarioRebalancingTCModule;
import org.matsim.project.prebookingStudy.run.routingModule.SchoolTrafficDrtRouteUpdater;
import org.matsim.project.prebookingStudy.run.routingModule.SchoolTrafficRouteCreator;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(
        name = "run",
        description = "run drt pre-booking study"
)
public class RunMATSimBenchmark implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--output", description = "root output folder", required = true)
    private String output;

    @CommandLine.Option(names = "--alpha", description = "travel time alpha", defaultValue = "2.0")
    private String alpha;

    @CommandLine.Option(names = "--beta", description = "travel time beta", defaultValue = "1200.0")
    private String beta;

    @CommandLine.Option(names = "--school-starting-time", description = "school starting time", defaultValue = "UNIFORM")
    private CaseStudyTool.SchoolStartingTime schoolStartingTime;

    @CommandLine.Option(names = "--service-scheme", description = "Service scheme", defaultValue = "DOOR_TO_DOOR")
    private CaseStudyTool.ServiceScheme serviceScheme;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "maximum number of runs", defaultValue = "1")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles reduced for each step", defaultValue = "5")
    private int stepSize;

    public static void main(String[] args) {
        new RunMATSimBenchmark().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        // Create a temporary config file in the same folder, so that multiple runs can be run in the cluster at the same time
        int taskId = (int) (System.currentTimeMillis() / 1000);
        File originalConfig = new File(configPath.toString());
        String temporaryConfig = configPath.getParent().toString() + "/temporary_" + taskId + ".config.xml";
        File copy = new File(temporaryConfig);
        try {
            FileUtils.copyFile(originalConfig, copy);
        } catch (IOException e) {
            e.printStackTrace();
        }

        CaseStudyTool caseStudyTool = new CaseStudyTool(alpha, beta, schoolStartingTime, serviceScheme);
        SchoolTripsAnalysis schoolTripsAnalysis = new SchoolTripsAnalysis();
        output = output + "-alpha_" + alpha + "-beta_" + beta + "-" + schoolStartingTime.toString() + "-" + serviceScheme.toString();

        if (!Files.exists(Path.of(output))) {
            Files.createDirectory(Path.of(output));
        }
        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
        tsvWriter.printRecord(SchoolTripsAnalysis.TITLE_ROW_KPI);
        tsvWriter.close();

        int maxFleetSize = fleetSize + stepSize * (steps - 1);
        while (fleetSize <= maxFleetSize) {
            String outputDirectory = output + "/fleet-size-" + fleetSize;

            // prepare config
            Config config = ConfigUtils.loadConfig(temporaryConfig, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
            MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
            DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.planCalcScore(), config.plansCalcRoute());
            DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next(); // By default, the first drt config group is the one we are using
            caseStudyTool.prepareCaseStudy(config, drtConfigGroup);
            drtConfigGroup.setVehiclesFile("drt-vehicles-with-depot/" + fleetSize + "-8_seater-drt-vehicles.xml");
            config.controler().setOutputDirectory(outputDirectory);

            Scenario scenario = ScenarioUtils.loadScenario(config);
            scenario.getPopulation().getFactory().getRouteFactories().setRouteFactory(DrtRoute.class, new DrtRouteFactory());

            Controler controler = new Controler(scenario);

            controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModule()));
            controler.addOverridingModule(new MultiModeDrtModule());
            controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeDrtConfig));
            controler.addOverridingModule(new AbstractModule() {
                @Override
                public void install() {
                    bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);
                }
            });

            // Adding the custom rebalancing target calculator
            for (DrtConfigGroup drtCfg : multiModeDrtConfig.getModalElements()) {
                controler.addOverridingQSimModule(new RuralScenarioRebalancingTCModule(drtCfg, 300));
                if (caseStudyTool.getServiceScheme() == CaseStudyTool.ServiceScheme.STOP_BASED) {
                    controler.addOverridingModule(new AbstractDvrpModeModule(drtCfg.getMode()) {
                        @Override
                        public void install() {
                            bindModal(DefaultMainLegRouter.RouteCreator.class).toProvider(
                                    new SchoolTrafficRouteCreator.SchoolTripsDrtRouteCreatorProvider(drtCfg));// not singleton
                            bindModal(DrtRouteUpdater.class).toProvider(new ModalProviders.AbstractProvider<>(getMode(), DvrpModes::mode) {
                                @Inject
                                private Population population;
                                @Inject
                                private Config config;
                                @Override
                                public SchoolTrafficDrtRouteUpdater get() {
                                    var travelTime = getModalInstance(TravelTime.class);
                                    Network network = getModalInstance(Network.class);
                                    return new SchoolTrafficDrtRouteUpdater(drtCfg, network, travelTime,
                                            getModalInstance(TravelDisutilityFactory.class), population, config);
                                }
                            }).asEagerSingleton();
                        }
                    });
                }
            }

            controler.run();

            // Analysis
            schoolTripsAnalysis.analyze(Path.of(outputDirectory));
            CSVPrinter resultsWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
            resultsWriter.printRecord(schoolTripsAnalysis.getOutputKPIRow());
            resultsWriter.close();

            String[] input = new String[]{outputDirectory};
            DrtVehicleStoppingTaskWriter.main(input);

            // If the all requests are served and on-time rate is approaching 100%, then we can stop the experiments sequence early
            double onTimeRate = Double.parseDouble(schoolTripsAnalysis.getOutputKPIRow().get(4));
            int numRequests = Integer.parseInt(schoolTripsAnalysis.getOutputKPIRow().get(1));
            int numServedRequests = Integer.parseInt(schoolTripsAnalysis.getOutputKPIRow().get(2));
            if (onTimeRate >= 0.99 && numServedRequests == numRequests) {
                maxFleetSize = Math.min(maxFleetSize, fleetSize + stepSize * 2); // max 2 more runs and then stop
            }
            fleetSize += stepSize;
        }

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));

        return 0;
    }
}
