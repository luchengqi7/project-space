package org.matsim.project.drtOperationStudy.analysis;

import org.matsim.contrib.drt.extension.preplanned.optimizer.WaitForStopTask;

import java.io.IOException;
import java.nio.file.Path;

public class PlotDrtStoppingTask {
    public static void main(String[] args) throws IOException {
        new UpdatedDrtTaskWriter(Path.of(args[0])).run(WaitForStopTask.TYPE);
    }
}
