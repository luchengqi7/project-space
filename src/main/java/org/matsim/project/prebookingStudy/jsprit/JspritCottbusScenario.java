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
import com.graphhopper.jsprit.analysis.toolbox.Plotter;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.io.problem.VrpXMLWriter;

import java.io.File;
import java.util.Collection;
import java.util.List;


public class JspritCottbusScenario {


    public static void main(String[] args) {
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

		/*
         * get a vehicle type-builder and build a type with the typeId "vehicleType" and one capacity dimension, i.e. weight, and capacity dimension value of 2
		 */
        final int WEIGHT_INDEX = 0;
        VehicleTypeImpl.Builder vehicleTypeBuilder = VehicleTypeImpl.Builder.newInstance("vehicleType")
            .addCapacityDimension(WEIGHT_INDEX, 4)
            .setMaxVelocity(30);
        VehicleType vehicleType = vehicleTypeBuilder.build();

		/*
         * get a vehicle-builder and build a vehicle located at (10,10) with type "vehicleType"
		 */
        Builder vehicleBuilder = Builder.newInstance("vehicle");
        vehicleBuilder.setStartLocation(Location.newInstance(10, 10));
        vehicleBuilder.setType(vehicleType);
        VehicleImpl vehicle = vehicleBuilder.build();

        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", WEIGHT_INDEX);

		/*
         * build services at the required locations, each with a capacity-demand of 1.
		 */
/*        Service service1 = Service.Builder.newInstance("service1").addSizeDimension(WEIGHT_INDEX, 1).setLocation(Location.newInstance(5, 7)).build();
        Service service2 = Service.Builder.newInstance("service2").addSizeDimension(WEIGHT_INDEX, 1).setLocation(Location.newInstance(5, 13)).build();

        Service service3 = Service.Builder.newInstance("service3").addSizeDimension(WEIGHT_INDEX, 1).setLocation(Location.newInstance(15, 7)).build();
        Service service4 = Service.Builder.newInstance("service4").addSizeDimension(WEIGHT_INDEX, 1).setLocation(Location.newInstance(15, 13)).build();*/
        //VehicleRoutingProblem.Builder vrpBuilder = useServiceAsFeeder(matsimDrtRequest2Jsprit, vehicle);

        /*
         *
         */
/*        Shipment shipment1 = Shipment.Builder.newInstance("shipment1")
            //.setName("myShipment")
            .setPickupLocation(Location.newInstance(2,7)).setDeliveryLocation(Location.newInstance(10,2))
            .addSizeDimension(WEIGHT_INDEX,1)*//*.addSizeDimension(1,50)*//*
            //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
            .setPickupServiceTime(900)
            .setDeliveryServiceTime(1000)
            .setPickupTimeWindow(new TimeWindow(900, 900))
            .setDeliveryTimeWindow(new TimeWindow(920, 1010))
            //.setMaxTimeInVehicle()
            //.setPriority()
            .build();*/
        VehicleRoutingProblem.Builder vrpBuilder = useShipmentAsFeeder(matsimDrtRequest2Jsprit, vehicle);

        VehicleRoutingProblem problem = vrpBuilder.build();
        //problem.getJobs();

		/*
         * get the algorithm out-of-the-box.
		 */
        VehicleRoutingAlgorithm algorithm = Jsprit.createAlgorithm(problem);
        //algorithm.setMaxIterations(100);

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
    }

    private static VehicleRoutingProblem.Builder useServiceAsFeeder(MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit, VehicleImpl vehicle) {
        List<Service> servicesList = (List<Service>) matsimDrtRequest2Jsprit.matsimRequestReader("useService");

        /*
         *
         */
        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
        vrpBuilder.addVehicle(vehicle);
        //vrpBuilder.addJob(service1).addJob(service2).addJob(service3).addJob(service4);
        for (Service service : servicesList) {
            vrpBuilder.addJob(service);
        }
        return vrpBuilder;
    }

    private static VehicleRoutingProblem.Builder useShipmentAsFeeder(MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit, VehicleImpl vehicle) {
        List<Shipment> shipmentsList = (List<Shipment>) matsimDrtRequest2Jsprit.matsimRequestReader("useShipment");

        /*
         *
         */
        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
        vrpBuilder.addVehicle(vehicle);
        //vrpBuilder.addJob(service1).addJob(service2).addJob(service3).addJob(service4);
        for (Shipment shipment : shipmentsList) {
            vrpBuilder.addJob(shipment);
        }
        return vrpBuilder;
    }

}
