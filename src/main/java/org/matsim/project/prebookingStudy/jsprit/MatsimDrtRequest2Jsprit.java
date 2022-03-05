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
import org.matsim.core.router.LeastCostPathCalculatorModule;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;
import java.net.URL;

import org.apache.log4j.Logger;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

public class MatsimDrtRequest2Jsprit {

    final static double PICKUP_SERVICE_TIME_IN_MATSIM = 0.;
    final static double DELIVERY_SERVICE_TIME_IN_MATSIM = 0.;

    //Config config;
    Scenario scenario;
    final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
    //For switching to the oneTaxi Scenario with prebooking (Can not read from the config directly, so must be specified)
    String dvrpMode;
    int capacityIndex;
    double maxTravelTimeAlpha;
    double maxTravelTimeBeta;
    double maxWaitTime;
    Network network;
    LeastCostPathCalculator router;

    private static final Logger LOG = Logger.getLogger(MatsimDrtRequest2Jsprit.class);

    // ================ For test purpose
    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", "taxi", 0);
    }

    MatsimDrtRequest2Jsprit(String matsimConfig, String dvrpMode, int capacityIndex) {
        this.dvrpMode = dvrpMode;
        this.capacityIndex = capacityIndex;

        URL fleetSpecificationUrl = null;
        Config config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
        this.scenario = ScenarioUtils.loadScenario(config);
        this.network = scenario.getNetwork();
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
            fleetSpecificationUrl = drtCfg.getVehiclesFileUrl(scenario.getConfig().getContext());

            this.maxTravelTimeAlpha = drtCfg.getMaxTravelTimeAlpha();
            this.maxTravelTimeBeta = drtCfg.getMaxTravelTimeBeta();
            this.maxWaitTime = drtCfg.getMaxWaitTime();
        }
        new FleetReader(dvrpFleetSpecification).parse(fleetSpecificationUrl);

        TravelTime travelTime = new FreeSpeedTravelTime();
        this.router = new SpeedyALTFactory().createPathCalculator
                (network, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);
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
            vehicleBuilder.setStartLocation(Location.Builder.newInstance().setId(scenario.getNetwork().getLinks().get(startLinkId).getId().toString()).setCoordinate(Coordinate.newInstance(startLinkLocationX, startLinkLocationY)).build());
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

        int counter = 0;
        int divisor = 1;
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
                        //ToDo: use TransportModeNetworkFilter or filter in NetworkUtils to filter the links for drt(/car)
                        pickupLocationId = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getId().toString();
                    }

                    pickupTime = originActivity.getEndTime().seconds();
                }
                counter++;
                if (counter % divisor == 0) {
                    LOG.info("Pickup # " + counter + " handled.");
                    //divisor = divisor * 2;
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
                        //ToDo: use TransportModeNetworkFilter or filter in NetworkUtils to filter the links for drt(/car)
                        deliveryLocationId = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getId().toString();
                    }
                }
                //counter++;
                if (counter % divisor == 0) {
                    LOG.info("Dropoff # " + counter + " handled.");
                    divisor = divisor * 2;
                }

                //use Shipment to create request for jsprit
                /*
                 *
                 */
                double speed = vehicleType.getMaxVelocity();
                // double detourFactor = 1.3;
                // double transportTime = EuclideanDistanceCalculator.calculateDistance(Location.newInstance(pickupLocationX, pickupLocationY).getCoordinate(), Location.newInstance(deliveryLocationX, deliveryLocationY).getCoordinate()) * detourFactor / speed;
                //ToDo: this parameter need to be calibrated? Or as a tunable parameter?

                double travelTime = router.calcLeastCostPath(network.getLinks().get(Id.createLinkId(pickupLocationId)).getToNode(), network.getLinks().get(Id.createLinkId(deliveryLocationId)).getToNode(), pickupTime, null, null).travelTime;
                //ToDo: change maximalWaitingtime to beta from config file
                double latestDeliveryTime = pickupTime + maxTravelTimeBeta + travelTime * maxTravelTimeAlpha;
                Shipment shipment = Shipment.Builder.newInstance(person.getId() + "#" + requestCount)
                        //.setName("myShipment")
                        .setPickupLocation(Location.Builder.newInstance().setId(pickupLocationId).setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY)).build())
                        .setDeliveryLocation(Location.Builder.newInstance().setId(deliveryLocationId).setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY)).build())
                        .addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
                        //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                        .setPickupServiceTime(PICKUP_SERVICE_TIME_IN_MATSIM)
                        .setDeliveryServiceTime(DELIVERY_SERVICE_TIME_IN_MATSIM)
                        .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + maxWaitTime))
                        //ToDo: remove travelTime?
                        .setDeliveryTimeWindow(new TimeWindow(pickupTime + travelTime, latestDeliveryTime))
                        //Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
                        //.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
                        //.setPriority()
                        .build();
                vrpBuilder.addJob(shipment);
                requestCount++;
            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");
        LOG.info("Request # " + counter + " handled in total!");

        //return requests
        return vrpBuilder;
    }

}
