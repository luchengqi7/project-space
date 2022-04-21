package org.matsim.project.prebookingStudy.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
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

    @CommandLine.Option(names = "--fleet-size", description = "fleet size", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "number of runs (pushing down from the initial fleet-size)", defaultValue = "1")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles reduced for each step", defaultValue = "10")
    private int stepSize;

    @CommandLine.Option(names = "--iters", description = "jsprit iteraions", defaultValue = "1000")
    private int jspritIterations;

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
        fleetSize += stepSize;
        for (int i = 0; i < steps; i++) {
            fleetSize -= stepSize;
            String outputDirectory = output + "/fleet-size-" + fleetSize;
            runJsprit(temporaryConfig, outputDirectory, false);
        }

        // Run infinite fleet size
        fleetSize = 400; //TODO improve this. Currently, using the largest fleet vehicle file we have
        runJsprit(temporaryConfig, output + "/infinite-fleet", true);

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));

        return 0;
    }

    private void runJsprit(String configPath, String outputDirectory, boolean infiniteFleetSize) throws IOException {
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setLastIteration(0);
        modifyDrtConfigs(MultiModeDrtConfigGroup.get(config));

        Controler controler = PreplannedDrtControlerCreator.createControler(config, false);

        var options = new MyPreplannedSchedulesCalculator.Options(infiniteFleetSize, false, jspritIterations);

        MultiModeDrtConfigGroup.get(config)
                .getModalElements()
                .forEach(drtConfig -> controler.addOverridingQSimModule(
                        new AbstractDvrpModeQSimModule(drtConfig.getMode()) {
                            @Override
                            protected void configureQSim() {
                                bindModal(PreplannedDrtOptimizer.PreplannedSchedules.class).toProvider(modalProvider(
                                        getter -> new MyPreplannedSchedulesCalculator(config, drtConfig,
                                                getter.getModal(FleetSpecification.class),
                                                getter.getModal(Network.class), getter.get(Population.class),
                                                options).calculate())).asEagerSingleton();
                            }
                        }));

        controler.run();

        // Post analysis
        SchoolTripsAnalysis analysis = new SchoolTripsAnalysis();
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
