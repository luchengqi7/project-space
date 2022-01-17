package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;
import java.net.URL;

import org.apache.log4j.Logger;

public class MatsimDrtRequest2Jsprit {

    //Config config;
    Scenario scenario;
    final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
    //For switching to the oneTaxi Scenario with prebooking (Can not read from the config directly, so must be specified)
    String dvrpMode;
    int capacityIndex;
    int maximalWaitingtime;
    Network network;

    private static final Logger LOG = Logger.getLogger(MatsimDrtRequest2Jsprit.class);

    // ================ For test purpose
    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", "taxi", 0, 60 * 15);
    }

    MatsimDrtRequest2Jsprit(String matsimConfig, String dvrpMode, int capacityIndex, int maximalWaitingtime) {
        this.dvrpMode = dvrpMode;
        this.capacityIndex = capacityIndex;
        this.maximalWaitingtime = maximalWaitingtime;

        URL fleetSpecificationUrl = null;
        Config config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
        this.scenario = ScenarioUtils.loadScenario(config);
        this.network = scenario.getNetwork();
        for (DrtConfigGroup drtCfg : ((MultiModeDrtConfigGroup) config.getModule("multiModeDrt")).getModalElements()) {
            fleetSpecificationUrl = drtCfg.getVehiclesFileUrl(scenario.getConfig().getContext());
        }
        new FleetReader(dvrpFleetSpecification).parse(fleetSpecificationUrl);
    }

    // ================ Vehicle Reader
    int matsimVehicleCapacityReader() {
        int capacity = 0;

        if (dvrpFleetSpecification.getVehicleSpecifications().values().stream().map(DvrpVehicleSpecification::getCapacity).distinct().count() == 1) {
            for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
                capacity = dvrpVehicleSpecification.getCapacity();
                break;
            }
        } else {
            throw new RuntimeException("Dvrp vehicles have different capacity/seats.");
        }

        return capacity;
    }

    VehicleRoutingProblem.Builder matsimVehicleReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType) {
        int vehicleCount = 0;

        for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
            Id<Link> startLinkId = dvrpVehicleSpecification.getStartLinkId();
            double startLinkLocationX;
            double startLinkLocationY;
            if ("oneTaxi".equals(dvrpMode)) {
                startLinkLocationX = scenario.getNetwork().getLinks().get(startLinkId).getCoord().getX();
                startLinkLocationY = scenario.getNetwork().getLinks().get(startLinkId).getCoord().getY();
            } else {
                startLinkLocationX = scenario.getNetwork().getLinks().get(startLinkId).getToNode().getCoord().getX();
                startLinkLocationY = scenario.getNetwork().getLinks().get(startLinkId).getToNode().getCoord().getY();
            }
            /*
             * get a vehicle-builder and build a vehicle located at (x,y) with type "vehicleType"
             */
            VehicleImpl.Builder vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicleSpecification.getId().toString());
            vehicleBuilder.setStartLocation(Location.newInstance(startLinkLocationX, startLinkLocationY));
            vehicleBuilder.setEarliestStart(dvrpVehicleSpecification.getServiceBeginTime());
            vehicleBuilder.setLatestArrival(dvrpVehicleSpecification.getServiceEndTime());
            vehicleBuilder.setType(vehicleType);

            VehicleImpl vehicle = vehicleBuilder.build();
            vrpBuilder.addVehicle(vehicle);
            vehicleCount++;
        }

        return vrpBuilder;
    }

    // ================ REQUEST Reader
    VehicleRoutingProblem.Builder matsimRequestReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType) {
        double pickupTime;
        double deliveryTime;
        double pickupLocationX;
        double pickupLocationY;
        String pickupLocationId;
        double deliveryLocationX;
        double deliveryLocationY;
        String deliveryLocationId;

        for (Person person : scenario.getPopulation().getPersons().values()) {
            int requestCount = 0;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                assert trip.getLegsOnly().size() == 1 : "Error: There exists a trip has more than one legs!";

                //originActivity
                {
                    Activity originActivity = trip.getOriginActivity();
                    Id<Link> activityLinkId = originActivity.getLinkId();
                    if (originActivity.getCoord() != null) {
                        pickupLocationX = originActivity.getCoord().getX();
                        pickupLocationY = originActivity.getCoord().getY();
                    } else {
                        pickupLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
                        pickupLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
                    }

                    if (activityLinkId != null) {
                        pickupLocationId = activityLinkId.toString();
                    } else {
                        pickupLocationId = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getId().toString();
                    }

                    pickupTime = originActivity.getEndTime().seconds();
                }

                //destinationActivity
                {
                    Activity destinationActivity = trip.getDestinationActivity();
                    Id<Link> activityLinkId = destinationActivity.getLinkId();
                    if (destinationActivity.getCoord() != null) {
                        deliveryLocationX = destinationActivity.getCoord().getX();
                        deliveryLocationY = destinationActivity.getCoord().getY();
                    } else {
                        deliveryLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
                        deliveryLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
                    }

                    if (activityLinkId != null) {
                        deliveryLocationId = activityLinkId.toString();
                    } else {
                        deliveryLocationId = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getId().toString();
                    }
                }

                //use Shipment to create request for jsprit
                /*
                 *
                 */
                double speed = vehicleType.getMaxVelocity();
                double detourFactor = 1.3;
                double transportTime = EuclideanDistanceCalculator.calculateDistance(Location.newInstance(pickupLocationX, pickupLocationY).getCoordinate(), Location.newInstance(deliveryLocationX, deliveryLocationY).getCoordinate()) * detourFactor / speed;
                double increment = 0.;
                //ToDo: this parameter need to be calibrated? Or as a tunable parameter?
                double poolingFactor = 1.5;
                Shipment shipment = Shipment.Builder.newInstance(person.getId() + "#" + requestCount)
                        //.setName("myShipment")
                        .setPickupLocation(Location.Builder.newInstance().setId(pickupLocationId).setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY)).build())
                        .setDeliveryLocation(Location.Builder.newInstance().setId(deliveryLocationId).setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY)).build())
                        .addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
                        //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                        .setPickupServiceTime(60)
                        .setDeliveryServiceTime(60)
                        .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + maximalWaitingtime))
                        //.setDeliveryTimeWindow(new TimeWindow(0, deliveryTime + DILIVERYTOLERANCETIME_OFFSET))
                        //Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour facyor) * time travel!
                        .setMaxTimeInVehicle(poolingFactor * transportTime + increment)
                        //.setPriority()
                        .build();
                vrpBuilder.addJob(shipment);
                requestCount++;
            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");

        //return requests
        return vrpBuilder;
    }

}
