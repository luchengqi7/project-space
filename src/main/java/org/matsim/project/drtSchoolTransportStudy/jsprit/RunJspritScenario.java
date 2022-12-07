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
package org.matsim.project.drtSchoolTransportStudy.jsprit;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoods;
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoodsFactory;
import com.graphhopper.jsprit.core.algorithm.ruin.distance.AvgServiceAndShipmentDistance;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.EuclideanCosts;
import com.graphhopper.jsprit.core.util.RandomNumberGeneration;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.core.util.UnassignedJobReasonTracker;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import org.matsim.project.drtSchoolTransportStudy.jsprit.listener.MyIterationEndsListener;
import org.matsim.project.drtSchoolTransportStudy.jsprit.utils.*;
import org.matsim.utils.MemoryObserver;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Random;

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

    @CommandLine.Option(names = "--nr-iter", description = "number of iterations", defaultValue = "100")
    private static int numberOfIterations;

    @CommandLine.Option(names = "--stats-output-path", description = "path for saving output file (problem-with-solution, output_trips, customer_stats, vehicle_stats)", required = true)
    private static Path statsOutputPath;

    @CommandLine.Option(names = "--enable-network-based-costs", description = "enable network-based transportCosts", defaultValue = "false")
    private static boolean enableNetworkBasedCosts;

    @CommandLine.Option(names = "--print-memory-interval", description = "set the time interval(s) for printing the memory usage in the log", defaultValue = "60")
    private static int memoryObserverInterval;

    @CommandLine.Option(names = "--max-velocity", description = "set the maximal velocity (m/s) for the fleet vehicle type", defaultValue = "10000000")
    private static int maxVelocity;

    @CommandLine.Option(names = "--OF", description = "Enum values: ${COMPLETION-CANDIDATES}", defaultValue = "JspritDefault")
    private MySolutionCostCalculatorFactory.ObjectiveFunctionType objectiveFunctionType;

    @CommandLine.Option(names = "--enable-graph-stream-viewer", description = "enable graphStreamViewer", defaultValue = "false")
    private static boolean enableGraphStreamViewer;

    @CommandLine.Option(names = "--school-traffic", description = "Enum values: ${COMPLETION-CANDIDATES}", defaultValue = "UNIFORM")
    private SchoolTrafficUtils.SchoolStartTimeScheme schoolStartTimeScheme;

    @CommandLine.Option(names = "--run-test", description = "if running the test for jsprit", defaultValue = "false")
    private static boolean isRunningTest;

    public UnassignedJobReasonTracker reasonTracker;

    @CommandLine.Option(names = "--enable-infinite-fleet", description = "enable infinite fleet size based on the input vehicle start locations(depots)", defaultValue = "false")
    private static boolean enableInfiniteFleet;

    @CommandLine.Option(names = "--random-seed", description = "set the random seed for the simulation", defaultValue = "4711")
    private static long randomSeed;

    @CommandLine.Option(names = "--enable-multi-thread", description = "enable multi-thread computing", defaultValue = "false")
    private static boolean enableMultithread;

    private VehicleRoutingTransportCosts transportCosts;

    private static final Logger LOG = LogManager.getLogger(RunJspritScenario.class);

    public static void main(String[] args) {
        new RunJspritScenario().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        MemoryObserver.start(memoryObserverInterval);

        /*
         * some preparation - create output folder
         */
        File dir = new File(statsOutputPath.toString());
        // if the directory does not exist, create it
        if (!dir.exists()) {
            System.out.println("creating directory " + statsOutputPath.toString());
            boolean result = dir.mkdir();
            if (result) System.out.println(statsOutputPath.toString() + " created");
        }

        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(matsimConfig.toString(), dvrpMode, capacityIndex);
        VehicleRoutingProblem.Builder vrpBuilder = new VehicleRoutingProblem.Builder();


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
        vrpBuilder = matsimDrtRequest2Jsprit.matsimVehicleReader(vrpBuilder, vehicleType, enableNetworkBasedCosts);


        //use Service to create requests
        //vrpBuilder.addJob(service1).addJob(service2).addJob(service3).addJob(service4);
        //vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader("useService", vrpBuilder);

        //use Shipment to create requests
        vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader(vrpBuilder, vehicleType, enableNetworkBasedCosts, schoolStartTimeScheme);


        /*
         * create routing costs for jsprit
         */
        StatisticUtils statisticUtils;
        if (enableNetworkBasedCosts) {
            // compute matrix
            transportCosts = MatrixBasedVrpCosts.calculateVrpCosts(matsimDrtRequest2Jsprit.getNetwork(), matsimDrtRequest2Jsprit.getLocationByLinkId());
            LOG.info("MatrixBased VrpCosts costs computed.");
            vrpBuilder.setRoutingCost(transportCosts);
            statisticUtils = new StatisticUtils(matsimDrtRequest2Jsprit.getConfig(), transportCosts, matsimDrtRequest2Jsprit.getServiceTimeInMatsim());
        } else {
            statisticUtils = new StatisticUtils(matsimDrtRequest2Jsprit.getConfig(), matsimDrtRequest2Jsprit.getServiceTimeInMatsim());
            transportCosts = new EuclideanCosts();
        }
/*        statisticUtils.setDesiredPickupTimeMap(matsimDrtRequest2Jsprit.getDesiredPickupTimeMap());
        statisticUtils.setDesiredDeliveryTimeMap(matsimDrtRequest2Jsprit.getDesiredDeliveryTimeMap());*/


        /*
         * build vehicle routing problem.
         */
        VehicleRoutingProblem problem;
        if(enableInfiniteFleet){
            // ================ default settings
            problem = vrpBuilder.build();
        }else {
            problem = vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE).build();
        }
        //problem.getJobs();


        /*
         * get the algorithm out-of-the-box.
         */
        //VehicleRoutingAlgorithm algorithm = Jsprit.createAlgorithm(problem);
        JobNeighborhoods jobNeighborhoods = new JobNeighborhoodsFactory().createNeighborhoods(problem, new AvgServiceAndShipmentDistance(problem.getTransportCosts()), (int) (problem.getJobs().values().size() * 0.5));
        jobNeighborhoods.initialise();
        //double maxCosts = TransportCostUtils.getInVehicleTimeCost() * jobNeighborhoods.getMaxDistance();
        double maxCosts = TransportCostUtils.getRequestRejectionCosts();
        MySolutionCostCalculatorFactory mySolutionCostCalculatorFactory = new MySolutionCostCalculatorFactory();
        SolutionCostCalculator objectiveFunction = mySolutionCostCalculatorFactory.getObjectiveFunction(problem, maxCosts, objectiveFunctionType, matsimDrtRequest2Jsprit.getConfig(), transportCosts);
        Random random = RandomNumberGeneration.getRandom();
        random.setSeed(randomSeed);
        int threads = enableMultithread ? Runtime.getRuntime().availableProcessors() : 1;
        VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem).setObjectiveFunction(objectiveFunction).setRandom(random).setProperty(Jsprit.Parameter.THREADS, threads + "").buildAlgorithm();
        LOG.info("The objective function used is " + objectiveFunctionType.toString());
        LOG.info("The random seed used is " + randomSeed);
        LOG.info("The thread number used is " + threads);
        algorithm.setMaxIterations(numberOfIterations);
        if(isRunningTest){
            reasonTracker = new UnassignedJobReasonTracker();
            algorithm.addListener(reasonTracker);
        }
        algorithm.getAlgorithmListeners().addListener(new MyIterationEndsListener());


        /*
         * and search a solution
         */
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();


        /*
         * get the best and print the Jsprit-related stats
         */
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        String solutionOutputFilename = (!statsOutputPath.toString().endsWith("/")) ? statsOutputPath + "/problem-with-solution.xml" : statsOutputPath + "problem-with-solution.xml";
        new VrpXMLWriter(problem, solutions).write(solutionOutputFilename);

        StatisticCollectorForIterationEndsListener statisticCollectorForIterationEndsListener = new StatisticCollectorForIterationEndsListener(matsimDrtRequest2Jsprit.getConfig());
        statisticCollectorForIterationEndsListener.writeOutputStats(statsOutputPath.toString());

        /*
         * print the MATSim-related stats
         */
        statisticUtils.writeConfig(statsOutputPath.toString());
        //print results to csv files
        statisticUtils.statsCollector(problem, bestSolution);
        statisticUtils.writeOutputTrips(statsOutputPath.toString());
        statisticUtils.writeCustomerStats(statsOutputPath.toString());
        statisticUtils.writeVehicleStats(statsOutputPath.toString(), problem, bestSolution);
        statisticUtils.writeSummaryStats(statsOutputPath.toString(), problem, bestSolution);


        MemoryObserver.stop();


        /*
         * plot
         */
        //new Plotter(problem,bestSolution).plot("output/plot.png","simple example");

        /*
        render problem and solution with GraphStream
         */
        if(enableGraphStreamViewer) {
            GraphStreamViewer graphStreamViewer = new GraphStreamViewer(problem, bestSolution);
            graphStreamViewer.labelWith(GraphStreamViewer.Label.ID).setRenderDelay(300).setGraphStreamFrameScalingFactor(2)/*.setRenderShipments(true)*/.display();
        }


        return 0;
    }

}
