package org.matsim.project.prebookingStudy.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.benchmark.DvrpBenchmarkTravelTimeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.analysis.SchoolTripsAnalysis;
import org.matsim.project.prebookingStudy.run.rebalancing.RuralScenarioRebalancingTCModule;
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
    private double alpha;

    @CommandLine.Option(names = "--beta", description = "travel time beta", defaultValue = "1200.0")
    private double beta;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "number of runs (pushing down from the initial fleet-size)", defaultValue = "1")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles reduced for each step", defaultValue = "10")
    private int stepSize;

    public static void main(String[] args) {
        new RunMATSimBenchmark().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(Path.of(output))) {
            Files.createDirectory(Path.of(output));
        }

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

        SchoolTripsAnalysis schoolTripsAnalysis = new SchoolTripsAnalysis();
        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv"), CSVFormat.TDF);
        tsvWriter.printRecord(SchoolTripsAnalysis.TITLE_ROW_KPI);
        tsvWriter.close();

        fleetSize += stepSize;
        for (int i = 0; i < steps; i++) {
            fleetSize -= stepSize;
            String outputDirectory = output + "/fleet-size-" + fleetSize;

            Config config = ConfigUtils.loadConfig(temporaryConfig, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
            MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
            DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.planCalcScore(), config.plansCalcRoute());

            DrtConfigGroup drtConfigGroup = multiModeDrtConfig.getModalElements().iterator().next(); // By default, the first drt config group is the one we are using
            drtConfigGroup.setMaxTravelTimeAlpha(alpha);
            drtConfigGroup.setMaxTravelTimeBeta(beta);
            drtConfigGroup.setMaxWaitTime(7200); //This constraint is no longer important in the school traffic case

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
            }

            controler.run();

            schoolTripsAnalysis.analyze(Path.of(outputDirectory));
            CSVPrinter resultsWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
            resultsWriter.printRecord(schoolTripsAnalysis.getOutputKPIRow());
            resultsWriter.close();

            String[] input = new String[]{outputDirectory};
            DrtVehicleStoppingTaskWriter.main(input);
        }

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));

        return 0;
    }
}
