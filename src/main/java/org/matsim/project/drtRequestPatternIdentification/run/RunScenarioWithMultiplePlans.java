package org.matsim.project.drtRequestPatternIdentification.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.project.drtRequestPatternIdentification.basicStructures.DemandsPatternCore;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@CommandLine.Command(
        name = "run-quantification-sequential",
        description = "quantify the DRT demands pattern with different plans"
)
public class RunScenarioWithMultiplePlans implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--plans-folder", description = "path to the folder of all plans", required = true)
    private String plansFolder;

    @CommandLine.Option(names = "--plans-variable-part", description = "unique part in the names of the plan, separated by empty space", arity = "1..*", required = true)
    private List<String> plansNames;

    @CommandLine.Option(names = "--output", description = "output directory", required = true)
    private String outputDirectory;

    public static void main(String[] args) {
        new RunScenarioWithMultiplePlans().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        CSVPrinter titleWriter = new CSVPrinter(new FileWriter(outputDirectory + "/demands-pattern-summary.tsv"), CSVFormat.TDF);
        titleWriter.printRecord("drt_plans", "num_of_trips", "trip_average_direct_duration", "shareability");
        titleWriter.close();

        for (String planNamePart : plansNames) {
            Config config = ConfigUtils.loadConfig(configPath.toString(), new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
            String inputPlansString = plansFolder + "/drt-plans-" + planNamePart + ".xml.gz";
            config.plans().setInputFile(inputPlansString);
            DemandsPatternCore demandsPatternCore = RunDemandQuantification.runQuantification(config);
            List<String> outputRow = Arrays.asList(planNamePart,
                    Integer.toString(demandsPatternCore.numOfTrips()),
                    Double.toString(demandsPatternCore.averageDirectTripDuration()),
                    Double.toString(demandsPatternCore.shareability()));
            CSVPrinter resultWriter = new CSVPrinter(new FileWriter(outputDirectory + "/demands-pattern-summary.tsv", true), CSVFormat.TDF);
            resultWriter.printRecord(outputRow);
            resultWriter.close();
        }


        return 0;
    }
}
