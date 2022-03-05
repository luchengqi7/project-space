package org.matsim.project.prebookingStudy.jsprit.utils;

import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.jsprit.MatsimDrtRequest2Jsprit;
import org.matsim.project.prebookingStudy.jsprit.NetworkBasedDrtVrpCosts;
import picocli.CommandLine;


import com.graphhopper.jsprit.analysis.toolbox.AlgorithmSearchProgressChartListener;
import com.graphhopper.jsprit.analysis.toolbox.Plotter;
import com.graphhopper.jsprit.analysis.toolbox.Plotter.Label;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.selector.SelectBest;
import com.graphhopper.jsprit.core.analysis.SolutionAnalyser;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.io.problem.VrpXMLReader;
//import com.graphhopper.jsprit.util.Examples;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

@CommandLine.Command(
        name = "run",
        description = "run Jsprit solution analyzer"
)
public class RunJspritSolutionAnalyzer implements MATSimAppCommand {
    //input path for oneTaxi: matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private static Path matsimConfig;

    @CommandLine.Option(names = "--solution-input-path", description = "path for feeding solution file", required = true)
    private static Path solutionInputPath;

    @CommandLine.Option(names = "--stats-output-path", description = "path for saving output file (problem-with-solution, output_trips, customer_stats, vehicle_stats)", required = true)
    private static Path statsOutputPath;

    @CommandLine.Option(names = "--enable-network-based-costs", description = "enable network-based transportCosts", defaultValue = "true")
    private static boolean enableNetworkBasedCosts;

    @CommandLine.Option(names = "--cache-size", description = "set the cache size limit of network-based transportCosts if network-based transportCosts is enabled!", defaultValue = "10000")
    private static int cacheSizeLimit;


    private static final Logger LOG = Logger.getLogger(RunJspritSolutionAnalyzer.class);

    public static void main(String[] args) {
        new RunJspritSolutionAnalyzer().execute(args);
    }

    public Integer call() throws Exception {

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


        // Get scenario (network)
        Scenario scenario = ScenarioUtils.loadScenario(ConfigUtils.loadConfig(matsimConfig.toString()));

        StatisticUtils statisticUtils;
        if (enableNetworkBasedCosts) {
            NetworkBasedDrtVrpCosts.Builder networkBasedDrtVrpCostsbuilder = new NetworkBasedDrtVrpCosts.Builder(scenario.getNetwork())
                    .enableCache(true)
                    .setCacheSizeLimit(cacheSizeLimit);
            if(cacheSizeLimit!=10000){
                LOG.info("The cache size limit of network-based transportCosts is (not the default value) and set to " + cacheSizeLimit);
            }
            VehicleRoutingTransportCosts transportCosts = networkBasedDrtVrpCostsbuilder.build();
            //vrpBuilder.setRoutingCost(transportCosts);
            LOG.info("network-based costs enabled!");
            statisticUtils = new StatisticUtils(transportCosts, MatsimDrtRequest2Jsprit.PICKUP_SERVICE_TIME_IN_MATSIM, MatsimDrtRequest2Jsprit.DELIVERY_SERVICE_TIME_IN_MATSIM);
        } else {
            statisticUtils = new StatisticUtils(MatsimDrtRequest2Jsprit.PICKUP_SERVICE_TIME_IN_MATSIM, MatsimDrtRequest2Jsprit.DELIVERY_SERVICE_TIME_IN_MATSIM);
        }

        /*
         * some preparation - create output folder
         */
        //Examples.createOutputFolder();

        /*
         * Build the problem.
         *
         * But define a problem-builder first.
         */
        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();

        /*
         * A solomonReader reads solomon-instance files, and stores the required information in the builder.
         */
        Collection<VehicleRoutingProblemSolution> solutions = new ArrayList<>();
        new VrpXMLReader(vrpBuilder, solutions).read(solutionInputPath.toString());

        /*
         * Finally, the problem can be built. By default, transportCosts are crowFlyDistances (as usually used for vrp-instances).
         */

        final VehicleRoutingProblem problem = vrpBuilder.build();

        //new Plotter(vrp).plot("output/pd_solomon_r101.png", "pd_r101");


/*        *//*
         * Define the required vehicle-routing algorithms to solve the above problem.
         *
         * The algorithm can be defined and configured in an xml-file.
         *//*
//		VehicleRoutingAlgorithm vra = new SchrimpfFactory().createAlgorithm(vrp);
        VehicleRoutingAlgorithm vra = Jsprit.createAlgorithm(problem);
        vra.getAlgorithmListeners().addListener(new AlgorithmSearchProgressChartListener("output/sol_progress.png"));
        *//*
         * Solve the problem.
         *
         *
         *//*
        Collection<VehicleRoutingProblemSolution> solutions = vra.searchSolutions();*/

        /*
         * Retrieve best solution.
         */
        VehicleRoutingProblemSolution bestSolution = new SelectBest().selectSolution(solutions);


        //print results to a csv file
        statisticUtils.statsCollector(problem, bestSolution);
        statisticUtils.writeOutputTrips(matsimConfig.toString(), statsOutputPath.toString());
        statisticUtils.writeCustomerStats(matsimConfig.toString(), statsOutputPath.toString());
        statisticUtils.writeVehicleStats(matsimConfig.toString(), statsOutputPath.toString(), problem);

        String solutionOutputFilename = (!statsOutputPath.toString().endsWith("/")) ? statsOutputPath.toString() + "/problem-with-solution.xml" : statsOutputPath.toString() + "problem-with-solution.xml";
        new VrpXMLWriter(problem, solutions).write(solutionOutputFilename);
        /*
         * print solution
         */
        //SolutionPrinter.print(solution);

/*        *//*
         * Plot solution.
         *//*
//		SolutionPlotter.plotSolutionAsPNG(vrp, solution, "output/pd_solomon_r101_solution.png","pd_r101");
        Plotter plotter = new Plotter(vrp, solution);
        plotter.setLabel(Label.SIZE);
        plotter.plot("output/pd_solomon_r101_solution.png", "pd_r101");

        //some stats
        SolutionAnalyser analyser = new SolutionAnalyser(vrp, solution, vrp.getTransportCosts());

        System.out.println("tp_distance: " + analyser.getDistance());
        System.out.println("tp_time: " + analyser.getTransportTime());
        System.out.println("waiting: " + analyser.getWaitingTime());
        System.out.println("service: " + analyser.getServiceTime());
        System.out.println("#picks: " + analyser.getNumberOfPickups());
        System.out.println("#deliveries: " + analyser.getNumberOfDeliveries());*/

        return 0;

    }

}
