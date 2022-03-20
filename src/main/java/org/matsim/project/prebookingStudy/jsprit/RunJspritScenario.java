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
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoods;
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoodsFactory;
import com.graphhopper.jsprit.core.algorithm.ruin.distance.AvgServiceAndShipmentDistance;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.EuclideanCosts;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.core.util.UnassignedJobReasonTracker;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.project.prebookingStudy.jsprit.utils.GraphStreamViewer;
import org.matsim.project.prebookingStudy.jsprit.utils.SchoolTrafficUtils;
import org.matsim.project.prebookingStudy.jsprit.utils.StatisticUtils;
import org.matsim.utils.MemoryObserver;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

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

    @CommandLine.Option(names = "--cache-size", description = "set the cache size limit of network-based transportCosts if network-based transportCosts is enabled!", defaultValue = "10000")
    private static int cacheSizeLimit;

    @CommandLine.Option(names = "--print-memory-interval", description = "set the time interval(s) for printing the memory usage in the log", defaultValue = "60")
    private static int memoryObserverInterval;

    @CommandLine.Option(names = "--max-velocity", description = "set the maximal velocity (m/s) for the fleet vehicle type", defaultValue = "0x1.fffffffffffffP+1023")
    private static int maxVelocity;

    @CommandLine.Option(names = "--OF", description = "Enum values: ${COMPLETION-CANDIDATES}", defaultValue = "JspritDefault")
    private MySolutionCostCalculatorFactory.ObjectiveFunctionType objectiveFunctionType;

    @CommandLine.Option(names = "--enable-graph-stream-viewer", description = "enable graphStreamViewer", defaultValue = "false")
    private static boolean enableGraphStreamViewer;

    @CommandLine.Option(names = "--school-traffic", description = "Enum values: ${COMPLETION-CANDIDATES}", defaultValue = "Disabled")
    private SchoolTrafficUtils.SchoolStartTimeScheme schoolStartTimeScheme;

    @CommandLine.Option(names = "--run-test", description = "if running the test for jsprit", defaultValue = "false")
    private static boolean isRunningTest;

    public UnassignedJobReasonTracker reasonTracker;

    @CommandLine.Option(names = "--enable-infinite-fleet", description = "enable infinite fleet size based on the input vehicle start locations(depots)", defaultValue = "false")
    private static boolean enableInfiniteFleet;

    public enum MatsimVrpCostsCalculatorType {NetworkBased, MatrixBased}

    @CommandLine.Option(names = "--vrp-costs-calculator", description = "Enum values: ${COMPLETION-CANDIDATES}", defaultValue = "NetworkBased")
    private MatsimVrpCostsCalculatorType matsimVrpCostsCalculatorType;

    private final Map<Id<Node>, Location> locationByNodeId = new IdMap<>(Node.class);
    public Map<Id<Node>, Location> getLocationByNodeId() {
        return locationByNodeId;
    }

    private VehicleRoutingTransportCosts transportCosts;

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
        File dir = new File(statsOutputPath.toString());
        // if the directory does not exist, create it
        if (!dir.exists()) {
            System.out.println("creating directory " + statsOutputPath.toString());
            boolean result = dir.mkdir();
            if (result) System.out.println(statsOutputPath.toString() + " created");
        }

        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(matsimConfig.toString(), dvrpMode, capacityIndex);
        VehicleRoutingProblem.Builder vrpBuilder = new VehicleRoutingProblem.Builder();
        StatisticUtils statisticUtils;
        if (enableNetworkBasedCosts) {
            if(matsimVrpCostsCalculatorType.equals(MatsimVrpCostsCalculatorType.NetworkBased)) {
                NetworkBasedDrtVrpCosts.Builder networkBasedDrtVrpCostsbuilder = new NetworkBasedDrtVrpCosts.Builder(matsimDrtRequest2Jsprit.getNetwork())
                        .enableCache(true)
                        .setCacheSizeLimit(cacheSizeLimit);
                if (cacheSizeLimit != 10000) {
                    LOG.info("The cache size limit of network-based transportCosts is (not the default value) and set to " + cacheSizeLimit);
                }
                transportCosts = networkBasedDrtVrpCostsbuilder.build();
                LOG.info("NetworkBased VrpCosts Calculator enabled!");
            } else if(matsimVrpCostsCalculatorType.equals(MatsimVrpCostsCalculatorType.MatrixBased)){
                // compute matrix
                transportCosts = MatrixBasedVrpCosts.calculateVrpCosts(matsimDrtRequest2Jsprit.getNetwork(), locationByNodeId);
                LOG.info("MatrixBased VrpCosts Calculator enabled!");
            }
            vrpBuilder.setRoutingCost(transportCosts);
            statisticUtils = new StatisticUtils(matsimDrtRequest2Jsprit.getConfig(), transportCosts, matsimDrtRequest2Jsprit.getServiceTimeInMatsim());
        } else {
            statisticUtils = new StatisticUtils(matsimDrtRequest2Jsprit.getConfig(), matsimDrtRequest2Jsprit.getServiceTimeInMatsim());
            transportCosts = new EuclideanCosts();
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
        vrpBuilder = matsimDrtRequest2Jsprit.matsimRequestReader(vrpBuilder, vehicleType, enableNetworkBasedCosts, schoolStartTimeScheme);
        statisticUtils.setDesiredPickupTimeMap(matsimDrtRequest2Jsprit.getDesiredPickupTimeMap());
        statisticUtils.setDesiredDeliveryTimeMap(matsimDrtRequest2Jsprit.getDesiredDeliveryTimeMap());

        // ================ default settings
        VehicleRoutingProblem problem;
        if(enableInfiniteFleet){
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
        double maxCosts = jobNeighborhoods.getMaxDistance();
        MySolutionCostCalculatorFactory mySolutionCostCalculatorFactory = new MySolutionCostCalculatorFactory();
        SolutionCostCalculator objectiveFunction = mySolutionCostCalculatorFactory.getObjectiveFunction(problem, maxCosts, objectiveFunctionType, matsimDrtRequest2Jsprit, transportCosts);
        VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem).setObjectiveFunction(objectiveFunction).buildAlgorithm();
        LOG.info("The objective function used is " + objectiveFunctionType.toString());
        algorithm.setMaxIterations(numberOfIterations);
        if(isRunningTest){
            reasonTracker = new UnassignedJobReasonTracker();
            algorithm.addListener(reasonTracker);
        }

        /*
         * and search a solution
         */
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();

        /*
         * get the best
         */
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);

        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        statisticUtils.writeConfig(statsOutputPath.toString());

        String solutionOutputFilename = (!statsOutputPath.toString().endsWith("/")) ? statsOutputPath.toString() + "/problem-with-solution.xml" : statsOutputPath.toString() + "problem-with-solution.xml";
        new VrpXMLWriter(problem, solutions).write(solutionOutputFilename);

        //print results to csv files
        statisticUtils.statsCollector(problem, bestSolution);
        statisticUtils.writeOutputTrips(statsOutputPath.toString());
        statisticUtils.writeCustomerStats(statsOutputPath.toString());
        statisticUtils.writeVehicleStats(statsOutputPath.toString(), problem, bestSolution);


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
