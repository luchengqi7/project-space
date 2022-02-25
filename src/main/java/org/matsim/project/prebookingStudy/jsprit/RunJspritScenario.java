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

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;
import org.apache.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import org.matsim.project.prebookingStudy.jsprit.utils.GraphStreamViewer;
import org.matsim.project.prebookingStudy.jsprit.utils.StatisticUtils;
import org.matsim.utils.MemoryObserver;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

@CommandLine.Command(
        name = "run",
        description = "run Jsprit scenario"
)
public class RunJspritScenario implements MATSimAppCommand {

    //input path for oneTaxi: matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private static Path matsimConfig;

    //mode for oneTaxi: "oneTaxi"
    @CommandLine.Option(names = "--mode", description = "dvrp mode", defaultValue = "drt")
    private static String dvrpMode;

    @CommandLine.Option(names = "--capacity-index", description = "index of capacity", defaultValue = "0")
    private static int capacityIndex;

    @CommandLine.Option(names = "--maximal-waiting-time", description = "maximal waiting time of passenger", defaultValue = "900")
    private static int maximalWaitingtime;

    @CommandLine.Option(names = "--nr-iter", description = "number of iterations", defaultValue = "100")
    private static int numberOfIterations;

    @CommandLine.Option(names = "--solution-output-path", description = "path for saving output file (solution)", defaultValue = "output/problem-with-solution.xml")
    private static Path solutionOutputPath;

    @CommandLine.Option(names = "--stats-output-path", description = "path for saving output file (stats, customer_stats, vehicle_stats)", defaultValue = "output/")
    private static Path statsOutputPath;

    @CommandLine.Option(names = "--enable-network-based-costs", description = "enable network-based transportCosts", defaultValue = "false")
    private static boolean enableNetworkBasedCosts;

    @CommandLine.Option(names = "--cache-size", description = "set the cache size limit of network-based transportCosts if network-based transportCosts is enabled!", defaultValue = "10000")
    private static int cacheSizeLimit;

    @CommandLine.Option(names = "--print-memory-interval", description = "set the time interval(s) for printing the memory usage in the log", defaultValue = "60")
    private static int memoryObserverInterval;

    @CommandLine.Option(names = "--max-velocity", description = "set the maximal velocity for the fleet vehicle type", defaultValue = "0x1.fffffffffffffP+1023")
    private static int maxVelocity;


    private static final Logger LOG = Logger.getLogger(RunJspritScenario.class);

    public static void main(String[] args) {
        new RunJspritScenario().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        MemoryObserver.start(memoryObserverInterval);

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

        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(matsimConfig.toString(), dvrpMode, capacityIndex, maximalWaitingtime);
        VehicleRoutingProblem.Builder vrpBuilder = new VehicleRoutingProblem.Builder();
        StatisticUtils statisticUtils;
        if (enableNetworkBasedCosts) {
            NetworkBasedDrtVrpCosts.Builder networkBasedDrtVrpCostsbuilder = new NetworkBasedDrtVrpCosts.Builder(matsimDrtRequest2Jsprit.network)
                    .enableCache(true)
                    .setCacheSizeLimit(cacheSizeLimit);
            if(cacheSizeLimit!=10000){
                LOG.info("The cache size limit of network-based transportCosts is (not the default value) and set to " + cacheSizeLimit);
            }
            VehicleRoutingTransportCosts transportCosts = networkBasedDrtVrpCostsbuilder.build();
            vrpBuilder.setRoutingCost(transportCosts);
            LOG.info("network-based costs enabled!");
            statisticUtils = new StatisticUtils(transportCosts);
        } else {
            statisticUtils = new StatisticUtils();
        }




        /*
         * get a vehicle type-builder and build a type with the typeId "vehicleType" and one capacity dimension, i.e. weight, and capacity dimension value of 2
         */
        VehicleTypeImpl.Builder vehicleTypeBuilder = VehicleTypeImpl.Builder.newInstance(dvrpMode + "-vehicle")
                .addCapacityDimension(capacityIndex, matsimDrtRequest2Jsprit.matsimVehicleCapacityReader())
                .setMaxVelocity(maxVelocity)
/*                .setFixedCost()
                .setCostPerDistance()
                .setCostPerTransportTime()
                .setCostPerWaitingTime()*/
                //.setCostPerServiceTime()
                ;
        VehicleType vehicleType = vehicleTypeBuilder.build();
        vrpBuilder = matsimDrtRequest2Jsprit.matsimVehicleReader(vrpBuilder, vehicleType);


        //use Service to create requests
        //vrpBuilder.addJob(service1).addJob(service2).addJob(service3).addJob(service4);
        //vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader("useService", vrpBuilder);

        //use Shipment to create requests
        vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader(vrpBuilder, vehicleType);


        // ================ default settings
        VehicleRoutingProblem problem = vrpBuilder.build();
        //problem.getJobs();

        /*
         * get the algorithm out-of-the-box.
         */
        VehicleRoutingAlgorithm algorithm = Jsprit.createAlgorithm(problem);
        algorithm.setMaxIterations(numberOfIterations);

        /*
         * and search a solution
         */
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();

        /*
         * get the best
         */
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);

        //print results to a csv file
        statisticUtils.printVerbose(problem, bestSolution);
        statisticUtils.writeStats(matsimConfig.toString(), statsOutputPath.toString());
        statisticUtils.writeCustomerStats(matsimConfig.toString(), statsOutputPath.toString());
        statisticUtils.writeVehicleStats(matsimConfig.toString(), statsOutputPath.toString(), problem);

        new VrpXMLWriter(problem, solutions).write(solutionOutputPath.toString());

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        MemoryObserver.stop();

        /*
         * plot
         */
        //new Plotter(problem,bestSolution).plot("output/plot.png","simple example");

        /*
        render problem and solution with GraphStream
         */
        GraphStreamViewer graphStreamViewer = new GraphStreamViewer(problem, bestSolution);
        graphStreamViewer.labelWith(GraphStreamViewer.Label.ID).setRenderDelay(300).setGraphStreamFrameScalingFactor(2)/*.setRenderShipments(true)*/.display();

        return 0;
    }

}
