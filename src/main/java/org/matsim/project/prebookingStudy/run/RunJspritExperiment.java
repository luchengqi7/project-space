package org.matsim.project.prebookingStudy.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.extension.preplanned.optimizer.PreplannedDrtOptimizer;
import org.matsim.contrib.drt.extension.preplanned.run.PreplannedDrtControlerCreator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.project.prebookingStudy.analysis.SchoolTripsAnalysis;
import org.matsim.project.prebookingStudy.jsprit.MyPreplannedSchedulesCalculator;
import org.matsim.project.prebookingStudy.jsprit.PreplannedSchedulesCalculator;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(
        name = "run-jsprit-experiment",
        description = "run jsprit experiment"
)
public class RunJspritExperiment implements MATSimAppCommand {
    private static final Logger log = Logger.getLogger(RunJspritExperiment.class);

    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--output", description = "root output folder", required = true)
    private String output;

    @CommandLine.Option(names = "--alpha", description = "travel time alpha", defaultValue = "2.0")
    private double alpha;

    @CommandLine.Option(names = "--beta", description = "travel time beta", defaultValue = "1200.0")
    private double beta;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "100")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "number of runs (pushing down from the initial fleet-size)", defaultValue = "1")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles reduced for each step", defaultValue = "5")
    private int stepSize;

    @CommandLine.Option(names = "--iters", description = "jsprit iteraions", defaultValue = "1000")
    private int jspritIterations;

    @CommandLine.Option(names = "--multi-thread", defaultValue = "false", description = "enable multi-threading to increase computation speed")
    private boolean multiThread;

    private final SchoolTripsAnalysis analysis = new SchoolTripsAnalysis();

    public static void main(String[] args) {
        new RunJspritExperiment().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(Path.of(output))) {
            Files.createDirectory(Path.of(output));
        }

        // Create a temporary config file in the same folder, so that multiple runs can be run in the cluster at the same time
        String temporaryConfig = createTemporaryConfig();

        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv"), CSVFormat.TDF);
        tsvWriter.printRecord(SchoolTripsAnalysis.TITLE_ROW_KPI);
        tsvWriter.close();

        // Run finite fleet sizes
        int maxFleetSize = fleetSize + stepSize * (steps - 1);
        while (fleetSize <= maxFleetSize){
            String outputDirectory = output + "/fleet-size-" + fleetSize;
            runJsprit(temporaryConfig, outputDirectory);

            // If the on-time rate is approaching 100%, then we can stop the experiments sequence early
            double onTimeRate = Double.parseDouble(analysis.getOutputKPIRow().get(4));
            int numRequests = Integer.parseInt(analysis.getOutputKPIRow().get(1));
            int numServedRequests = Integer.parseInt(analysis.getOutputKPIRow().get(2));
            if (onTimeRate >= 0.99 && numServedRequests == numRequests){
                maxFleetSize = Math.min(maxFleetSize, fleetSize + stepSize * 2); // 2 more runs and then finish
            }
            fleetSize += stepSize;
        }

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));

        return 0;
    }

    private void runJsprit(String configPath, String outputDirectory) throws IOException {
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setLastIteration(0);
        modifyDrtConfigs(MultiModeDrtConfigGroup.get(config));

        Controler controler = PreplannedDrtControlerCreator.createControler(config, false);

        var options = new PreplannedSchedulesCalculator.Options(false, false, jspritIterations, multiThread);

        MultiModeDrtConfigGroup.get(config)
                .getModalElements()
                .forEach(drtConfig -> controler.addOverridingQSimModule(
                        new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
                            @Override
                            protected void configureQSim() {
                                bindModal(PreplannedDrtOptimizer.PreplannedSchedules.class).toProvider(modalProvider(
                                        getter -> new PreplannedSchedulesCalculator(drtConfig,
                                                getter.getModal(FleetSpecification.class),
                                                getter.getModal(Network.class), getter.get(Population.class),
                                                options).calculate())).asEagerSingleton();
                            }
                        }));

        controler.run();

        // Post analysis
        analysis.analyze(Path.of(outputDirectory));
        CSVPrinter resultsWriter = new CSVPrinter(new FileWriter(output + "/result-summary.tsv", true), CSVFormat.TDF);
        resultsWriter.printRecord(analysis.getOutputKPIRow());
        resultsWriter.close();

//        String[] input = new String[]{outputDirectory};
//        DrtVehicleStoppingTaskWriter.main(input);  //TODO modification is needed for the plotting script
    }

    private void modifyDrtConfigs(MultiModeDrtConfigGroup multiModeDrtConfigGroup) {
        for (DrtConfigGroup drtCfg : multiModeDrtConfigGroup.getModalElements()) {
            drtCfg.setVehiclesFile("drt-vehicles-with-depot/" + fleetSize + "-8_seater-drt-vehicles.xml");
            drtCfg.setMaxTravelTimeAlpha(alpha);
            drtCfg.setMaxTravelTimeBeta(beta);
            drtCfg.setMaxWaitTime(7200); //This constraint is no longer important in the school traffic case
            if (drtCfg.getRebalancingParams().isPresent()) {
                log.warn("The rebalancing parameter set is defined for drt mode: "
                        + drtCfg.getMode()
                        + ". It will be ignored. No rebalancing will happen.");
                drtCfg.removeParameterSet(drtCfg.getRebalancingParams().get());
            }
        }
    }

    private String createTemporaryConfig() {
        int taskId = (int) (System.currentTimeMillis() / 1000);
        File originalConfig = new File(configPath.toString());
        String temporaryConfig = configPath.getParent().toString() + "/temporary_" + taskId + ".config.xml";
        File copy = new File(temporaryConfig);
        try {
            FileUtils.copyFile(originalConfig, copy);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return temporaryConfig;
    }
}
