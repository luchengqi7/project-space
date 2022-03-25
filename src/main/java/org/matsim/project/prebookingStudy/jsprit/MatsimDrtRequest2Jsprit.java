package org.matsim.project.prebookingStudy.jsprit;

import com.google.common.base.Preconditions;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
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
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.HashMap;
import java.util.List;
import java.net.URL;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.project.prebookingStudy.jsprit.utils.SchoolTrafficUtils;

public class MatsimDrtRequest2Jsprit {

    Config config;
    Scenario scenario;
    final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
    //For switching to the oneTaxi Scenario with prebooking (Can not read from the config directly, so must be specified)
    String dvrpMode;
    int capacityIndex;
    double maxTravelTimeAlpha;
    double maxTravelTimeBeta;
    double maxWaitTime;
    double serviceTimeInMatsim;
    private final Network network;
    final LeastCostPathCalculator router;
    final Map<String, Double> desiredPickupTimeMap = new HashMap<>();
    final Map<String, Double> desiredDeliveryTimeMap = new HashMap<>();

    public Config getConfig() {
        return config;
    }
    public double getServiceTimeInMatsim() {
        return serviceTimeInMatsim;
    }
    public Network getNetwork() {
        return network;
    }
    public Map<String, Double> getDesiredPickupTimeMap() {
        return desiredPickupTimeMap;
    }
    public Map<String, Double> getDesiredDeliveryTimeMap() {
        return desiredDeliveryTimeMap;
    }

    private static final Logger LOG = Logger.getLogger(MatsimDrtRequest2Jsprit.class);

    private final Map<Id<Node>, Location> locationByNodeId = new IdMap<>(Node.class);
    public Map<Id<Node>, Location> getLocationByNodeId() {
        return locationByNodeId;
    }

    // ================ For test purpose
    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", "taxi", 0);
    }

    MatsimDrtRequest2Jsprit(String matsimConfig, String dvrpMode, int capacityIndex) {
        this.dvrpMode = dvrpMode;
        this.capacityIndex = capacityIndex;

        URL fleetSpecificationUrl = null;
        config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
        this.scenario = ScenarioUtils.loadScenario(config);
        this.network = scenario.getNetwork();
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
            fleetSpecificationUrl = drtCfg.getVehiclesFileUrl(scenario.getConfig().getContext());

            this.maxTravelTimeAlpha = drtCfg.getMaxTravelTimeAlpha();
            this.maxTravelTimeBeta = drtCfg.getMaxTravelTimeBeta();
            this.maxWaitTime = drtCfg.getMaxWaitTime();

            this.serviceTimeInMatsim = drtCfg.getStopDuration();
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

    VehicleRoutingProblem.Builder matsimVehicleReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType, boolean enableNetworkBasedCosts, RunJspritScenario.MatsimVrpCostsCalculatorType matsimVrpCostsCalculatorType) {
        int vehicleCount = 0;

        if (enableNetworkBasedCosts){
            if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)){
                for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
                    Id<Link> startLinkId = dvrpVehicleSpecification.getStartLinkId();
                    Node startNode;
                    if ("oneTaxi".equals(dvrpMode)) {
                        throw new RuntimeException("One Taxi scenario do not have nodes");
                    }

                    startNode = scenario.getNetwork().getLinks().get(startLinkId).getToNode();
                    computeLocationIfAbsent(locationByNodeId, startNode);
                }
            }
        }

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
            if (enableNetworkBasedCosts){
                if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)){
                    Node startNode = scenario.getNetwork().getLinks().get(startLinkId).getToNode();
                    vehicleBuilder.setStartLocation(locationByNodeId.get(startNode.getId()));
                } else if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.NetworkBased)){
                    vehicleBuilder.setStartLocation(Location.Builder.newInstance().setId(scenario.getNetwork().getLinks().get(startLinkId).getId().toString()).setCoordinate(Coordinate.newInstance(startLinkLocationX, startLinkLocationY)).build());
                }
            } else {
                vehicleBuilder.setStartLocation(Location.Builder.newInstance().setId(scenario.getNetwork().getLinks().get(startLinkId).getId().toString()).setCoordinate(Coordinate.newInstance(startLinkLocationX, startLinkLocationY)).build());
            }
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
    VehicleRoutingProblem.Builder matsimRequestReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType, boolean enableNetworkBasedCosts, RunJspritScenario.MatsimVrpCostsCalculatorType matsimVrpCostsCalculatorType, SchoolTrafficUtils.SchoolStartTimeScheme schoolStartTimeScheme) {

        if (enableNetworkBasedCosts){
            if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)){
                // collect pickup/dropoff locations
                for (Person person : scenario.getPopulation().getPersons().values()) {
                    if ("oneTaxi".equals(dvrpMode)) {
                        throw new RuntimeException("One Taxi scenario do not have nodes");
                    }

                    List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
                    for (TripStructureUtils.Trip trip : trips) {
                        Preconditions.checkState(trip.getLegsOnly().size() == 1, "A trip has more than one leg.");

                        var originActivity = trip.getOriginActivity();
                        var originNode = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getToNode();
                        computeLocationIfAbsent(locationByNodeId, originNode);

                        var destinationActivity = trip.getDestinationActivity();
                        var destinationNode = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getToNode();
                        computeLocationIfAbsent(locationByNodeId, destinationNode);
                    }
                }
            }
        }

        double pickupTime;
        double deliveryTime;
        Node pickupToNode = null;
        double pickupLocationX;
        double pickupLocationY;
        String pickupLocationId;
        Node deliveryToNode = null;
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
                        pickupToNode = NetworkUtils.getNearestLink(network, originActivity.getCoord()).getToNode();
                    } else {
                        pickupLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
                        pickupLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
                        pickupToNode = network.getLinks().get(activityLinkId).getToNode();
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
                String destinationActivityType;
                {
                    Activity destinationActivity = trip.getDestinationActivity();
                    Id<Link> activityLinkId = destinationActivity.getLinkId();
                    destinationActivityType = destinationActivity.getType();
                    if (destinationActivity.getCoord() != null) {
                        deliveryLocationX = destinationActivity.getCoord().getX();
                        deliveryLocationY = destinationActivity.getCoord().getY();
                        deliveryToNode = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getToNode();
                    } else {
                        deliveryLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
                        deliveryLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
                        deliveryToNode = network.getLinks().get(activityLinkId).getToNode();
                    }

                    if (activityLinkId != null) {
                        deliveryLocationId = activityLinkId.toString();
                    } else {
                        //ToDo: use TransportModeNetworkFilter or filter in NetworkUtils to filter the links for drt(/car)
                        deliveryLocationId = NetworkUtils.getNearestLink(network, destinationActivity.getCoord()).getId().toString();
                    }

                    deliveryTime = destinationActivity.getStartTime().seconds();
                }
                //counter++;
                if (counter % divisor == 0) {
                    LOG.info("Dropoff # " + counter + " handled.");
                    divisor = divisor * 2;
                }

                String requestId = person.getId() + "#" + requestCount;
                Location originLocation = null;
                Location destinationLocation = null;
                double travelTime;
                double speed = vehicleType.getMaxVelocity();
                if(enableNetworkBasedCosts) {
                    if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)){
                        originLocation = locationByNodeId.get(pickupToNode.getId());
                        destinationLocation = locationByNodeId.get(deliveryToNode.getId());
                    } else if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.NetworkBased)){
                        originLocation = Location.Builder.newInstance().setId(pickupLocationId).setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY)).build();
                        destinationLocation = Location.Builder.newInstance().setId(deliveryLocationId).setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY)).build();
                    }
                    travelTime = router.calcLeastCostPath(network.getLinks().get(Id.createLinkId(pickupLocationId)).getToNode(), network.getLinks().get(Id.createLinkId(deliveryLocationId)).getToNode(), pickupTime, null, null).travelTime;
                } else {
                    originLocation = Location.Builder.newInstance().setId(pickupLocationId).setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY)).build();
                    destinationLocation = Location.Builder.newInstance().setId(deliveryLocationId).setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY)).build();
                    travelTime = EuclideanDistanceCalculator.calculateDistance(Location.Builder.newInstance().setId(pickupLocationId).setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY)).build().getCoordinate(), Location.Builder.newInstance().setId(deliveryLocationId).setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY)).build().getCoordinate()) / speed;
                }
                double latestDeliveryTime;
                if(schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.Disabled)) {
                    /*
                     * use Shipment to create request for jsprit
                     */
                    latestDeliveryTime = pickupTime + maxTravelTimeBeta + travelTime * maxTravelTimeAlpha;
                    Shipment shipment = Shipment.Builder.newInstance(requestId)
                            //.setName("myShipment")
                            .setPickupLocation(originLocation)
                            .setDeliveryLocation(destinationLocation)
                            .addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
                            //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                            .setPickupServiceTime(serviceTimeInMatsim)
                            .setDeliveryServiceTime(serviceTimeInMatsim)
                            .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + maxWaitTime))
                            //ToDo: remove travelTime?
                            .setDeliveryTimeWindow(new TimeWindow(pickupTime + travelTime, latestDeliveryTime))
                            //Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
                            //.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
                            //.setPriority()
                            .build();
                    vrpBuilder.addJob(shipment);

                    //save the desiredPickupTime and desiredDeliveryTime into maps
                    desiredPickupTimeMap.put(requestId, pickupTime);
                    desiredDeliveryTimeMap.put(requestId, deliveryTime);
                } else if(schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.SchoolType)) {
                    /*
                     * use Shipment to create request for jsprit
                     */
                    latestDeliveryTime = SchoolTrafficUtils.identifySchoolStartTime(schoolStartTimeScheme, destinationActivityType);
                    double timeBetweenPickUpAndLatestDelivery = maxTravelTimeAlpha * travelTime + maxTravelTimeBeta;
                    Shipment shipment = Shipment.Builder.newInstance(requestId)
                            //.setName("myShipment")
                            .setPickupLocation(originLocation)
                            .setDeliveryLocation(destinationLocation)
                            .addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
                            //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                            .setPickupServiceTime(serviceTimeInMatsim)
                            .setDeliveryServiceTime(serviceTimeInMatsim)
                            .setPickupTimeWindow(new TimeWindow(latestDeliveryTime - timeBetweenPickUpAndLatestDelivery, latestDeliveryTime))
                            //ToDo: remove travelTime?
                            .setDeliveryTimeWindow(new TimeWindow(latestDeliveryTime - timeBetweenPickUpAndLatestDelivery, latestDeliveryTime))
                            //Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
                            //.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
                            //.setPriority()
                            .build();
                    vrpBuilder.addJob(shipment);

                    //save the desiredPickupTime and desiredDeliveryTime into maps
                    desiredPickupTimeMap.put(requestId, latestDeliveryTime - timeBetweenPickUpAndLatestDelivery);
                    desiredDeliveryTimeMap.put(requestId, latestDeliveryTime);
                } else if(schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.Eight)) {
                    /*
                     * use Shipment to create request for jsprit
                     */
                    latestDeliveryTime = SchoolTrafficUtils.identifySchoolStartTime(schoolStartTimeScheme, destinationActivityType);
                    Shipment shipment = Shipment.Builder.newInstance(requestId)
                            //.setName("myShipment")
                            .setPickupLocation(originLocation)
                            .setDeliveryLocation(destinationLocation)
                            .addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
                            //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                            .setPickupServiceTime(serviceTimeInMatsim)
                            .setDeliveryServiceTime(serviceTimeInMatsim)
                            .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + maxWaitTime))
                            //ToDo: remove travelTime?
                            .setDeliveryTimeWindow(new TimeWindow(pickupTime, latestDeliveryTime))
                            //Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
                            //.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
                            //.setPriority()
                            .build();
                    vrpBuilder.addJob(shipment);

                    //save the desiredPickupTime and desiredDeliveryTime into maps
                    desiredPickupTimeMap.put(requestId, pickupTime);
                    desiredDeliveryTimeMap.put(requestId, latestDeliveryTime);
                }
                requestCount++;
            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");
        LOG.info("Request # " + counter + " handled in total!");

        //return requests
        return vrpBuilder;
    }

    private Location computeLocationIfAbsent(Map<Id<Node>, Location> locationByNodeId, Node node) {
        return locationByNodeId.computeIfAbsent(node.getId(), nodeId -> Location.Builder.newInstance()
                .setId(node.getId() + "")
                .setIndex(locationByNodeId.size())
                .setCoordinate(Coordinate.newInstance(node.getCoord().getX(), node.getCoord().getY()))
                .build());
    }

}
