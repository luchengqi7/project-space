package org.matsim.project.prebookingStudy.jsprit;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.util.DistanceUtils;
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
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.project.prebookingStudy.jsprit.utils.SchoolTrafficUtils;

import com.google.common.base.Preconditions;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.util.Coordinate;
import com.graphhopper.jsprit.core.util.EuclideanDistanceCalculator;

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
	//final Map<String, Double> desiredPickupTimeMap = new HashMap<>();
	//final Map<String, Double> desiredDeliveryTimeMap = new HashMap<>();

	public Config getConfig() {
		return config;
	}

	public double getServiceTimeInMatsim() {
		return serviceTimeInMatsim;
	}

	public Network getNetwork() {
		return network;
	}

/*	public Map<String, Double> getDesiredPickupTimeMap() {
		return desiredPickupTimeMap;
	}

	public Map<String, Double> getDesiredDeliveryTimeMap() {
		return desiredDeliveryTimeMap;
	}*/

	private static final Logger LOG = Logger.getLogger(MatsimDrtRequest2Jsprit.class);

	private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);

	public Map<Id<Link>, Location> getLocationByLinkId() {
		return locationByLinkId;
	}

	// ================ For test purpose
	public static void main(String[] args) {
		MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(
				"/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml",
				"taxi", 0);
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
		this.router = new SpeedyALTFactory().createPathCalculator(network,
				new OnlyTimeDependentTravelDisutility(travelTime), travelTime);
	}

	// ================ Vehicle Reader
	int matsimVehicleCapacityReader() {
		int capacity = 0;

		if (dvrpFleetSpecification.getVehicleSpecifications()
				.values()
				.stream()
				.map(DvrpVehicleSpecification::getCapacity)
				.distinct()
				.count() == 1) {
			for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications()
					.values()) {
				capacity = dvrpVehicleSpecification.getCapacity();
				break;
			}
		} else {
			throw new RuntimeException("Dvrp vehicles have different capacity/seats.");
		}

		return capacity;
	}

	VehicleRoutingProblem.Builder matsimVehicleReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType,
			boolean enableNetworkBasedCosts,
			RunJspritScenario.MatsimVrpCostsCalculatorType matsimVrpCostsCalculatorType) {
		int vehicleCount = 0;

		if (enableNetworkBasedCosts) {
			if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)) {
				for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications()
						.values()) {
					Id<Link> startLinkId = dvrpVehicleSpecification.getStartLinkId();
					if ("oneTaxi".equals(dvrpMode)) {
						throw new RuntimeException("One Taxi scenario do not have nodes");
					}

					var startLink = scenario.getNetwork().getLinks().get(startLinkId);
					collectLocationIfAbsent(startLink);
				}
			}
		}

		for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications()
				.values()) {
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
			VehicleImpl.Builder vehicleBuilder = VehicleImpl.Builder.newInstance(
					dvrpVehicleSpecification.getId().toString());
			if (enableNetworkBasedCosts) {
				if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)) {
					var startLink = scenario.getNetwork().getLinks().get(startLinkId);
					vehicleBuilder.setStartLocation(locationByLinkId.get(startLink.getId()));
				} else if (matsimVrpCostsCalculatorType.equals(
						RunJspritScenario.MatsimVrpCostsCalculatorType.NetworkBased)) {
					vehicleBuilder.setStartLocation(Location.Builder.newInstance()
							.setId(scenario.getNetwork().getLinks().get(startLinkId).getId().toString())
							.setCoordinate(Coordinate.newInstance(startLinkLocationX, startLinkLocationY))
							.build());
				}
			} else {
				vehicleBuilder.setStartLocation(Location.Builder.newInstance()
						.setId(scenario.getNetwork().getLinks().get(startLinkId).getId().toString())
						.setCoordinate(Coordinate.newInstance(startLinkLocationX, startLinkLocationY))
						.build());
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
	VehicleRoutingProblem.Builder matsimRequestReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType,
			boolean enableNetworkBasedCosts,
			RunJspritScenario.MatsimVrpCostsCalculatorType matsimVrpCostsCalculatorType,
			SchoolTrafficUtils.SchoolStartTimeScheme schoolStartTimeScheme) {

		if (enableNetworkBasedCosts) {
			if (matsimVrpCostsCalculatorType.equals(RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)) {
				// collect pickup/dropoff locations
				for (Person person : scenario.getPopulation().getPersons().values()) {
					if ("oneTaxi".equals(dvrpMode)) {
						throw new RuntimeException("One Taxi scenario do not have nodes");
					}

					List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
					for (TripStructureUtils.Trip trip : trips) {
						Preconditions.checkState(trip.getLegsOnly().size() == 1, "A trip has more than one leg.");

						var originActivity = trip.getOriginActivity();
						var originLink = NetworkUtils.getNearestLink(network, originActivity.getCoord());
						collectLocationIfAbsent(originLink);

						var destinationActivity = trip.getDestinationActivity();
						var destinationLink = NetworkUtils.getNearestLink(network, destinationActivity.getCoord());
						collectLocationIfAbsent(destinationLink);
					}
				}
			}
		}

		double pickupTime;
		double deliveryTime;
		Link pickupLink = null;
		double pickupLocationX;
		double pickupLocationY;
		String pickupLocationId;
		Link deliveryLink = null;
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
				double walkingTime;
				{
					Activity originActivity = trip.getOriginActivity();
					Id<Link> activityLinkId = originActivity.getLinkId();
					if (originActivity.getCoord() != null) {
						pickupLocationX = originActivity.getCoord().getX();
						pickupLocationY = originActivity.getCoord().getY();
						pickupLink = NetworkUtils.getNearestLink(network, originActivity.getCoord());

						//calculate walking time between originActivity location and pickup location
						double walkingDistance = DistanceUtils.calculateDistance(pickupLink.getCoord(),
								originActivity.getCoord());
						double walkingSpeed = config.plansCalcRoute().getTeleportedModeSpeeds().get(TransportMode.walk);
						double distanceFactor = config.plansCalcRoute()
								.getBeelineDistanceFactors()
								.get(TransportMode.walk);
						walkingTime = walkingDistance * distanceFactor / walkingSpeed;
					} else {
						pickupLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
						pickupLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
						pickupLink = network.getLinks().get(activityLinkId);

						//calculate walking time between originActivity location and pickup location
						walkingTime = 0.;
					}

					if (activityLinkId != null) {
						pickupLocationId = activityLinkId.toString();
					} else {
						//ToDo: use TransportModeNetworkFilter or filter in NetworkUtils to filter the links for drt(/car)
						pickupLocationId = NetworkUtils.getNearestLink(network, originActivity.getCoord())
								.getId()
								.toString();
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
						deliveryLink = NetworkUtils.getNearestLink(network, destinationActivity.getCoord());
					} else {
						deliveryLocationX = network.getLinks().get(activityLinkId).getToNode().getCoord().getX();
						deliveryLocationY = network.getLinks().get(activityLinkId).getToNode().getCoord().getY();
						deliveryLink = network.getLinks().get(activityLinkId);
					}
					if (pickupLink.getId().toString().equals(deliveryLink.getId().toString())) {
						continue;  // If the departure location and destination location of the DRT trips is the same, then skip
					}

					if (activityLinkId != null) {
						deliveryLocationId = activityLinkId.toString();
					} else {
						//ToDo: use TransportModeNetworkFilter or filter in NetworkUtils to filter the links for drt(/car)
						deliveryLocationId = NetworkUtils.getNearestLink(network, destinationActivity.getCoord())
								.getId()
								.toString();
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
				if (enableNetworkBasedCosts) {
					if (matsimVrpCostsCalculatorType.equals(
							RunJspritScenario.MatsimVrpCostsCalculatorType.MatrixBased)) {
						originLocation = locationByLinkId.get(pickupLink.getId());
						destinationLocation = locationByLinkId.get(deliveryLink.getId());
					} else if (matsimVrpCostsCalculatorType.equals(
							RunJspritScenario.MatsimVrpCostsCalculatorType.NetworkBased)) {
						originLocation = Location.Builder.newInstance()
								.setId(pickupLocationId)
								.setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY))
								.build();
						destinationLocation = Location.Builder.newInstance()
								.setId(deliveryLocationId)
								.setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY))
								.build();
					}
					travelTime = router.calcLeastCostPath(
							network.getLinks().get(Id.createLinkId(pickupLocationId)).getToNode(),
							network.getLinks().get(Id.createLinkId(deliveryLocationId)).getToNode(), pickupTime, null,
							null).travelTime;
				} else {
					originLocation = Location.Builder.newInstance()
							.setId(pickupLocationId)
							.setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY))
							.build();
					destinationLocation = Location.Builder.newInstance()
							.setId(deliveryLocationId)
							.setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY))
							.build();
					travelTime = EuclideanDistanceCalculator.calculateDistance(Location.Builder.newInstance()
							.setId(pickupLocationId)
							.setCoordinate(Coordinate.newInstance(pickupLocationX, pickupLocationY))
							.build()
							.getCoordinate(), Location.Builder.newInstance()
							.setId(deliveryLocationId)
							.setCoordinate(Coordinate.newInstance(deliveryLocationX, deliveryLocationY))
							.build()
							.getCoordinate()) / speed;
				}
				double latestDeliveryTime;
				if (schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.Disabled)) {
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
							.setPickupTimeWindow(new TimeWindow(pickupTime + walkingTime, pickupTime + maxWaitTime))
							//ToDo: remove travelTime?
							.setDeliveryTimeWindow(
									new TimeWindow(pickupTime + walkingTime + travelTime, latestDeliveryTime))
							//Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
							//.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
							//.setPriority()
							.build();
					vrpBuilder.addJob(shipment);

/*					//save the desiredPickupTime and desiredDeliveryTime into maps
					desiredPickupTimeMap.put(requestId, pickupTime + walkingTime);
					desiredDeliveryTimeMap.put(requestId, latestDeliveryTime);*/
				} else if (schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.SchoolType)) {
					/*
					 * use Shipment to create request for jsprit
					 */
					latestDeliveryTime = SchoolTrafficUtils.identifySchoolStartTime(schoolStartTimeScheme,
							destinationActivityType);
					double timeBetweenPickUpAndLatestDelivery = maxTravelTimeAlpha * travelTime + maxTravelTimeBeta;
					Shipment shipment = Shipment.Builder.newInstance(requestId)
							//.setName("myShipment")
							.setPickupLocation(originLocation)
							.setDeliveryLocation(destinationLocation)
							.addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
							//.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
							.setPickupServiceTime(serviceTimeInMatsim)
							.setDeliveryServiceTime(serviceTimeInMatsim)
							.setPickupTimeWindow(new TimeWindow(
									latestDeliveryTime - timeBetweenPickUpAndLatestDelivery + walkingTime,
									latestDeliveryTime))
							//ToDo: remove travelTime?
							.setDeliveryTimeWindow(new TimeWindow(
									latestDeliveryTime - timeBetweenPickUpAndLatestDelivery + walkingTime,
									latestDeliveryTime))
							//Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
							//.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
							//.setPriority()
							.build();
					vrpBuilder.addJob(shipment);

/*					//save the desiredPickupTime and desiredDeliveryTime into maps
					desiredPickupTimeMap.put(requestId, latestDeliveryTime - timeBetweenPickUpAndLatestDelivery + walkingTime);
					desiredDeliveryTimeMap.put(requestId, latestDeliveryTime);*/
				} else if (schoolStartTimeScheme.equals(SchoolTrafficUtils.SchoolStartTimeScheme.Eight)) {
					/*
					 * use Shipment to create request for jsprit
					 */
					latestDeliveryTime = SchoolTrafficUtils.identifySchoolStartTime(schoolStartTimeScheme,
							destinationActivityType);
					Shipment shipment = Shipment.Builder.newInstance(requestId)
							//.setName("myShipment")
							.setPickupLocation(originLocation)
							.setDeliveryLocation(destinationLocation)
							.addSizeDimension(capacityIndex, 1)/*.addSizeDimension(1,50)*/
							//.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
							.setPickupServiceTime(serviceTimeInMatsim)
							.setDeliveryServiceTime(serviceTimeInMatsim)
							.setPickupTimeWindow(new TimeWindow(pickupTime + walkingTime, latestDeliveryTime))
							//ToDo: remove travelTime?
							.setDeliveryTimeWindow(new TimeWindow(pickupTime + walkingTime, latestDeliveryTime))
							//Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour factor) * time travel!
							//.setMaxTimeInVehicle(maxTravelTimeAlpha * travelTime + maxTravelTimeBeta)
							//.setPriority()
							.build();
					vrpBuilder.addJob(shipment);

/*					//save the desiredPickupTime and desiredDeliveryTime into maps
					desiredPickupTimeMap.put(requestId, pickupTime + walkingTime);
					desiredDeliveryTimeMap.put(requestId, latestDeliveryTime);*/
				}
				requestCount++;
			}
		}
		//PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");
		LOG.info("Request # " + counter + " handled in total!");

		//return requests
		return vrpBuilder;
	}

	private Location collectLocationIfAbsent(Link link) {
		return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
				.setId(link.getId() + "")
				.setIndex(locationByLinkId.size())
				.setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
				.build());
	}
}
