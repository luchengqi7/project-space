/*
 * Licensed to GraphHopper GmbH under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * GraphHopper GmbH licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.analysis.toolbox.GraphStreamViewer;
import com.graphhopper.jsprit.analysis.toolbox.GraphStreamViewer.Label;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

@CommandLine.Command(
        name = "run",
        description = "run Jsprit Cottbus scenario"
)
public class JspritCottbusScenario implements MATSimAppCommand {

    //input path for oneTaxi: matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private static Path matsimConfig;

    //mode for oneTaxi: "oneTaxi"
    @CommandLine.Option(names = "--mode", description = "dvrp mode", defaultValue = "drt")
    private static String dvrpMode;

    @CommandLine.Option(names = "--capacity-index", description = "index of capacity", defaultValue = "0")
    private static int capacityIndex;

    public static void main(String[] args) {
        new JspritCottbusScenario().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        /*
         * some preparation - create output folder
         */
        File dir = new File("output");
        // if the directory does not exist, create it
        if (!dir.exists()) {
            System.out.println("creating directory ./output");
            boolean result = dir.mkdir();
            if (result) System.out.println("./output created");
        }

        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(matsimConfig.toString(), dvrpMode, capacityIndex);
        VehicleRoutingProblem.Builder vrpBuilder = new VehicleRoutingProblem.Builder();




        /*
         * get a vehicle type-builder and build a type with the typeId "vehicleType" and one capacity dimension, i.e. weight, and capacity dimension value of 2
         */
        VehicleTypeImpl.Builder vehicleTypeBuilder = VehicleTypeImpl.Builder.newInstance(dvrpMode + "-vehicle")
                .addCapacityDimension(capacityIndex, matsimDrtRequest2Jsprit.matsimVehicleCapacityReader())
                .setMaxVelocity(30);
        VehicleType vehicleType = vehicleTypeBuilder.build();
        vrpBuilder = matsimDrtRequest2Jsprit.matsimVehicleReader(vrpBuilder, vehicleType);


        //use Service to create requests
        //vrpBuilder.addJob(service1).addJob(service2).addJob(service3).addJob(service4);
        //vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader("useService", vrpBuilder);

        //use Shipment to create requests
        vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader(vrpBuilder);


        // ================ default settings
        VehicleRoutingProblem problem = vrpBuilder.build();
        //problem.getJobs();

        /*
         * get the algorithm out-of-the-box.
         */
        VehicleRoutingAlgorithm algorithm = Jsprit.createAlgorithm(problem);
        algorithm.setMaxIterations(100);

        /*
         * and search a solution
         */
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();

        /*
         * get the best
         */
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);

        new VrpXMLWriter(problem, solutions).write("output/problem-with-solution.xml");

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        /*
         * plot
         */
        //new Plotter(problem,bestSolution).plot("output/plot.png","simple example");

        /*
        render problem and solution with GraphStream
         */
        new GraphStreamViewer(problem, bestSolution).labelWith(Label.ID).setRenderDelay(200).display();

        return 0;
    }

}
