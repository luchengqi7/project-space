package org.matsim.project.drtOperationStudy.analysis;

import org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter;
import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;

import java.io.IOException;
import java.nio.file.Path;

public class PlotDrtStoppingTask {
    public static void main(String[] args) throws IOException {
        DrtVehicleStoppingTaskWriter writer = new DrtVehicleStoppingTaskWriter(Path.of(args[0]));
        writer.addingCustomizedTaskToAnalyze(WaitForStopTask.TYPE); // Add additional task type to analyze
        writer.run(WaitForStopTask.TYPE); // All non-standard task type needs to be given (regardless if they are analyzed or not) Otherwise, the event reader will not work properly
    }
}
