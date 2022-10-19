package org.matsim.project.drtOperationStudy.run.caseStudy;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.optimizer.insertion.IncrementalStopDurationEstimator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.drt.schedule.StopDurationEstimator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.drtOperationStudy.analysis.DrtPerformanceQuantification;
import org.matsim.project.drtSchoolTransportStudy.run.dummyTraffic.DvrpBenchmarkTravelTimeModuleFixedTT;
import org.matsim.project.drtSchoolTransportStudy.run.rebalancing.RuralScenarioRebalancingTCModule;
import org.matsim.project.utils.LinearDrtStopDurationEstimator;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RunOnlineStrategy implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--output", description = "root output folder", required = true)
    private String output;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "maximum number of runs", defaultValue = "1")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles reduced for each step", defaultValue = "5")
    private int stepSize;

    @CommandLine.Option(names = "--seed", description = "number of vehicles reduced for each step", defaultValue = "4711")
    private long seed;

    public static void main(String[] args) {
        new RunOnlineStrategy().execute(args);
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

        if (!Files.exists(Path.of(output))) {
            Files.createDirectory(Path.of(output));
        }

        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
        tsvWriter.printRecord("Fleet_size", "Rejections", "Total_driving_time");
        tsvWriter.close();

        int maxFleetSize = fleetSize + stepSize * (steps - 1);
        while (fleetSize <= maxFleetSize) {
            String outputDirectory = output + "/fleet-size-" + fleetSize;
            Config config = ConfigUtils.loadConfig(temporaryConfig, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
            MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
            DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.planCalcScore(), config.plansCalcRoute());
            DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next(); // By default, the first drt config group is the one we are using
            drtConfigGroup.vehiclesFile = "drt-vehicles/" + fleetSize + "-8_seater-drt-vehicles.xml";
            config.controler().setOutputDirectory(outputDirectory);
            config.global().setRandomSeed(seed);

            Scenario scenario = ScenarioUtils.loadScenario(config);
            scenario.getPopulation().getFactory().getRouteFactories().setRouteFactory(DrtRoute.class, new DrtRouteFactory());

            Controler controler = new Controler(scenario);

            controler.addOverridingModule(new DvrpModule(new DvrpBenchmarkTravelTimeModuleFixedTT(0)));
            controler.addOverridingModule(new MultiModeDrtModule());
            controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeDrtConfig));
            controler.addOverridingModule(new AbstractModule() {
                @Override
                public void install() {
                    bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);
                }
            });

            for (DrtConfigGroup drtCfg : multiModeDrtConfig.getModalElements()) {
                // Use the rebalancing strategy for this rural area scenario
                controler.addOverridingQSimModule(new RuralScenarioRebalancingTCModule(drtCfg, 300));
                // Use linear incremental stop duration
                controler.addOverridingModule(new AbstractDvrpModeModule(drtCfg.getMode()) {
                    @Override
                    public void install() {
                        bindModal(StopDurationEstimator.class).toInstance((vehicle, dropoffRequests, pickupRequests) -> drtCfg.stopDuration * (dropoffRequests.size() + pickupRequests.size()));
                        bindModal(IncrementalStopDurationEstimator.class).toInstance(new LinearDrtStopDurationEstimator(drtCfg.stopDuration));
                    }
                });
            }

            controler.run();
            DrtPerformanceQuantification performanceQuantification = new DrtPerformanceQuantification();
            performanceQuantification.analyze(Path.of(outputDirectory), 0, "not_applicable");
            double totalDrivingTime = performanceQuantification.getTotalDrivingTime();
            int rejections = performanceQuantification.getRejections();

            CSVPrinter resultWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
            resultWriter.printRecord(Integer.toString(fleetSize), Integer.toString(rejections), Double.toString(totalDrivingTime));
            resultWriter.close();

            if (rejections == 0) {
                break;
            }

            fleetSize += stepSize;
        }

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));

        return 0;
    }
}
