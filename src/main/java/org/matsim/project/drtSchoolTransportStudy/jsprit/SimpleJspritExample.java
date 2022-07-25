package org.matsim.project.drtSchoolTransportStudy.jsprit;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.reporting.SolutionPrinter;
import com.graphhopper.jsprit.core.util.Solutions;

import java.util.Collection;

public class SimpleJspritExample {
    public static void main(String[] args) {
        // Create 3 passenger demands
        Shipment drtDemand1 = Shipment.Builder.newInstance("drt-trip-1")
                .setName("drt-request")
                .setPickupLocation(Location.newInstance(2, 7)).setDeliveryLocation(Location.newInstance(10, 2))
                .addSizeDimension(0, 1)
                .setPickupServiceTime(1).setDeliveryServiceTime(1)
                .setPickupTimeWindow(TimeWindow.newInstance(20, 35))
                .setMaxTimeInVehicle(15)
                .build();

        Shipment drtDemand2 = Shipment.Builder.newInstance("drt-trip-2")
                .setName("drt-request")
                .setPickupLocation(Location.newInstance(3, 1)).setDeliveryLocation(Location.newInstance(12, 6))
                .addSizeDimension(0, 1)
                .setPickupServiceTime(1).setDeliveryServiceTime(1)
                .setPickupTimeWindow(TimeWindow.newInstance(0, 15))
                .setMaxTimeInVehicle(15)
                .build();

        Shipment drtDemand3 = Shipment.Builder.newInstance("drt-trip-3")
                .setName("drt-request")
                .setPickupLocation(Location.newInstance(12, 2)).setDeliveryLocation(Location.newInstance(5, 3))
                .addSizeDimension(0, 1)
                .setPickupServiceTime(1).setDeliveryServiceTime(1)
                .setPickupTimeWindow(TimeWindow.newInstance(30, 45))
                .setMaxTimeInVehicle(15)
                .build();

        // Create vehicles: 1 vehicle type, 2 vehicles
        VehicleTypeImpl vehicleType = VehicleTypeImpl.Builder.newInstance("drt-vehicle")
                .addCapacityDimension(0, 8)
                .setMaxVelocity(1)
                .build();

        VehicleImpl vehicle1 = VehicleImpl.Builder.newInstance("drt-vehicle-1")
                .setType(vehicleType)
                .setStartLocation(Location.newInstance(0, 0)).setEndLocation(Location.newInstance(10, 10)) // This vehicle goes to end location at the end of the service
                .build();

        VehicleImpl vehicle2 = VehicleImpl.Builder.newInstance("drt-vehicle-2")
                .setType(vehicleType)
                .setStartLocation(Location.newInstance(10, 0))  //This vehicle does not need to go to specific place at the end
                .build();

        // Build problem
        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
        vrpBuilder.addJob(drtDemand1).addJob(drtDemand2).addJob(drtDemand3).addVehicle(vehicle1).addVehicle(vehicle2);
        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        VehicleRoutingProblem problem = vrpBuilder.build();


//        VehicleRoutingTransportCostsMatrix.Builder builder = VehicleRoutingTransportCostsMatrix.Builder.newInstance(false);
//        VehicleRoutingTransportCostsMatrix matrix = builder.addTransportTime("fromLocationId","toLocationId", 10).build();
//        vrpBuilder.setRoutingCost(matrix);

        // Define the algorithm
        VehicleRoutingAlgorithm algorithm = Jsprit.createAlgorithm(problem);

        // Solve the problem
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);

        // print results
        SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);

        // write result as xml
//        new VrpXMLWriter(problem, solutions).write("scenarios/cottbus/output/jsprit-example/optimization-results.xml");
    }
}
