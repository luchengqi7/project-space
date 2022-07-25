package org.matsim.project.drtSchoolTransportStudy.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Objects;

@CommandLine.Command(
        name = "post-analysis",
        description = "Run analysis for finsihed runs"
)
public class RunPostAnalysis implements MATSimAppCommand {
    @CommandLine.Option(names = "--directory", description = "path to root output directory", required = true)
    private Path directory;

    @CommandLine.Option(names = "--departure-delay-analysis", description = "enable departure delay analysis", defaultValue = "false")
    private boolean includeDepartureDelayAnalysis;

    public static void main(String[] args) {
        new RunPostAnalysis().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        File rootDirectory = new File(directory.toString());
        SchoolTripsAnalysis schoolTripsAnalysis = new SchoolTripsAnalysis();
        schoolTripsAnalysis.setIncludeDepartureDelayAnalysis(includeDepartureDelayAnalysis);

        CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(rootDirectory.getAbsolutePath() + "/result-summary.tsv"), CSVFormat.TDF);
        tsvWriter.printRecord(SchoolTripsAnalysis.TITLE_ROW_KPI);
        tsvWriter.close();

        for (File folder: Objects.requireNonNull(rootDirectory.listFiles())) {
            if (folder.isDirectory()){
                schoolTripsAnalysis.analyze(folder.toPath());
                CSVPrinter resultsWriter = new CSVPrinter(new FileWriter(rootDirectory.getAbsolutePath() + "/result-summary.tsv", true), CSVFormat.TDF);
                resultsWriter.printRecord(schoolTripsAnalysis.getOutputKPIRow());
                resultsWriter.close();
            }
        }

        return 0;
    }
}
